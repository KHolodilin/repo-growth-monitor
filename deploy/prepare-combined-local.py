from pathlib import Path
import json,secrets,base64
import subprocess,sys
r=Path(__file__).resolve().parents[1]
private=r/'deploy/tmp';private.mkdir(parents=True,exist_ok=True)
config=private/'combined-runtime.local.yml'
if config.exists():
 settings=json.loads(config.read_text());password=settings['collector']['datasource']['password']
 if settings['collector']['datasource']['url'] != 'jdbc:postgresql://postgres:5432/collector':
  sys.exit('Migrate the existing Collector database before changing its datasource.')
else:
 password=secrets.token_urlsafe(32);accept=secrets.token_urlsafe(40);resolve=secrets.token_urlsafe(40)
 settings={
  'rgm':{'planner':{'enabled':False},'collector':{'enabled':True},
   'credentials':{'enabled':True,'default-ref':'local-existing-token','active-key-id':'local-v1','encryption-keys':{'local-v1':base64.b64encode(secrets.token_bytes(32)).decode()},'cache-lease':'2h'},
   'orchestration':{'enabled':True,'allow-http':True,'nodes':[{'key':'local-combined','pool':'default','endpoint':'http://127.0.0.1:8080','enabled':True,'service-token':accept,'resolve-token':resolve}]}},
  'collector':{
   'storage':{'enabled':True},'datasource':{'url':'jdbc:postgresql://postgres:5432/collector','username':'collector','password':password},
   'http':{'enabled':True,'clients':[{'origin':'rgm','token':accept}]},
   'credentials':{'enabled':True,'ttl':'2h','maximum-size':100,'allow-http':True,'origins':[{'origin':'rgm','endpoint':'http://127.0.0.1:8080','service-token':resolve}]},
   'execution':{'enabled':True},
   'delivery':{'enabled':True,'allow-http':True,'destinations':[{'ref':'rgm-results','origin':'rgm','endpoint':'http://127.0.0.1:8080/internal/v1/collector-results'}]}
  }
 }
 config.write_text(json.dumps(settings,indent=2)+'\n')
# Provision on every preparation, including stacks with an existing product volume.
subprocess.run(['docker','compose','up','-d','--wait','postgres'],cwd=r,check=True)
psql=['docker','compose','exec','-T','postgres','psql','-XAt','-v','ON_ERROR_STOP=1','-U','postgres','-d','postgres']
def sql(statement):
 return subprocess.run(psql,input=statement,text=True,cwd=r,check=True,capture_output=True).stdout.strip()

if not sql("SELECT 1 FROM pg_roles WHERE rolname='collector'"):
 sql("CREATE ROLE collector LOGIN PASSWORD '" + password.replace("'", "''") + "'")
owner=sql("SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname='collector'")
if not owner:
 sql("CREATE DATABASE collector OWNER collector")
elif owner != 'collector':
 sys.exit('Existing collector database has a different owner.')
print('Collector database ready on postgres. Local keys preserved.')
