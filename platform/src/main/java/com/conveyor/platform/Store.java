package com.conveyor.platform;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class Store {
  public final JdbcTemplate db;
  private final TransactionTemplate tx;

  public Store(JdbcTemplate db, PlatformTransactionManager manager) {
    this.db = db;
    this.tx = new TransactionTemplate(manager);
  }

  // Serialize mutations within this database; the lock is released on commit or rollback.
  public <T> T atomic(Supplier<T> action) {
    return tx.execute(
        status -> {
          db.execute("select pg_advisory_xact_lock(8301)");
          return action.get();
        });
  }

  public Optional<ObjectNode> find(String kind, String id) {
    return db
        .query(
            "select body::text from records where kind=? and id=?",
            (r, i) -> D.parse(r.getString(1)),
            kind,
            id)
        .stream()
        .findFirst();
  }

  public ObjectNode get(String kind, String id) {
    return find(kind, id)
        .orElseThrow(() -> new Fault(404, "NOT_FOUND", kind + " " + id + " not found"));
  }

  public List<ObjectNode> all(String kind) {
    return db.query(
        "select body::text from records where kind=? order by id",
        (r, i) -> D.parse(r.getString(1)),
        kind);
  }

  public void put(String kind, String id, ObjectNode body) {
    db.update(
        "insert into records(kind,id,body) values (?,?,?::jsonb) on conflict(kind,id) do update set"
            + " body=excluded.body",
        kind,
        id,
        body.toString());
  }

  public ObjectNode create(String kind, ObjectNode body) {
    String id = D.id(body);
    D.state(find(kind, id).isEmpty(), "Duplicate identifier");
    ObjectNode copy =
        body.deepCopy().put("id", id).put("createdAt", java.time.Instant.now().toString());
    put(kind, id, copy);
    return copy;
  }

  public String owner(String cap) {
    return find("_owner", cap).map(x -> D.s(x, "state")).orElse("LOCAL");
  }

  public void owner(String cap, String state) {
    put("_owner", cap, D.obj().put("state", state));
  }

  public boolean claim(String eventId, String consumer) {
    return db.update(
            "insert into processed_events(event_id,consumer_name) values (?,?) on conflict do"
                + " nothing",
            eventId,
            consumer)
        > 0;
  }
}
