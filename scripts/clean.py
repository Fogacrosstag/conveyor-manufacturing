from pathlib import Path
import shutil
root=Path(__file__).resolve().parents[1]
for path in list(root.rglob('target'))+list(root.rglob('__pycache__')):
    if path.is_dir(): shutil.rmtree(path)
print('Removed build/cache output. Database volumes were preserved.')
