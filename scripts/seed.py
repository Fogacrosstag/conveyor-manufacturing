#!/usr/bin/env python3
from client import api,stable

ROUTE=['CUTTING','WELDING','MACHINING','ASSEMBLY','ELECTRICAL_INSTALLATION','TESTING','PACKAGING']
MATERIALS=[('MOTOR','Industrial Drive Motor',1),('BELT','Conveyor Belt',1),('FRAME','Steel Frame',1),('DRIVE-PULLEY','Drive Pulley',1),('RETURN-PULLEY','Return Pulley',1),('ROLLER','Support Roller',12)]
TYPES=['DIMENSIONAL_INSPECTION','WELDING_INSPECTION','ELECTRICAL_INSPECTION','FUNCTIONAL_TEST','FINAL_INSPECTION']
def seed():
    for code,name,_ in MATERIALS:
        api('material','/materials',{'id':stable(code),'materialCode':code,'name':name,'unitOfMeasure':'EA','minimumStockLevel':10},key='seed-material-'+code)
        api('material','/materials/receive',{'materialId':stable(code),'quantity':10000},key='seed-receipt-'+code)
    for i,code in enumerate(ROUTE,1):
        api('production-planning','/planning/work-centers',{'id':stable(code),'equipmentId':stable('EQ-'+code),'code':code,'name':code.replace('_',' ').title(),'sequence':i,'durationMinutes':[90,90,60,120,60,60,30][i-1],'capacity':1,'recoveryAllowanceMinutes':30},key='seed-wc-'+code)
    for code,name,kind in [('BC-200','Belt Conveyor','BELT_CONVEYOR'),('RC-150','Roller Conveyor','ROLLER_CONVEYOR'),('CC-300','Chain Conveyor','CHAIN_CONVEYOR'),('MC-100','Modular Conveyor','MODULAR_CONVEYOR')]:
        api('products','/products',{'id':stable(code),'code':code,'name':name,'productType':kind},key='seed-product-'+code)
    product=stable('BC-200');spec=stable('BC-200-SPEC-1');bom=stable('BC-200-BOM-1')
    api('engineering','/engineering/specifications',{'id':spec,'productId':product,'revision':1,'checks':[{'type':t,'minimum':9,'maximum':11,'unit':'demonstration-units'} for t in TYPES]},key='seed-spec')
    api('engineering',f'/engineering/specifications/{spec}/approve',{},key='seed-spec-approve')
    api('engineering','/engineering/boms',{'id':bom,'productId':product,'revision':1,'specificationId':spec,'items':[{'materialId':stable(code),'quantity':qty,'unitOfMeasure':'EA'} for code,_,qty in MATERIALS]},key='seed-bom')
    api('engineering',f'/engineering/boms/{bom}/approve',{},key='seed-bom-approve')
    return product,bom
if __name__=='__main__':print('Seeded product and approved BOM:',seed())
