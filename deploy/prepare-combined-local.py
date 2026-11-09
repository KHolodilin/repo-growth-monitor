from pathlib import Path
import json,secrets,base64
r=Path(__file__).resolve().parents[1]
private=r/'deploy/tmp';private.mkdir(parents=True,exist_ok=True)
config=private/'combined-runtime.local.yml';dbEnv=private/'collector-db.local.env'
if config.exists():
 settings=json.loads(config.read_text());password=settings['collector']['datasource']['password']
else:
 password=secrets.token_urlsafe(32);accept=secrets.token_urlsafe(40);resolve=secrets.token_urlsafe(40)
 settings={
  'rgm':{'planner':{'enabled':False},'collector':{'enabled':True},
   'credentials':{'enabled':True,'default-ref':'local-existing-token','active-key-id':'local-v1','encryption-keys':{'local-v1':base64.b64encode(secrets.token_bytes(32)).decode()},'cache-lease':'2h'},
   'orchestration':{'enabled':True,'allow-http':True,'nodes':[{'key':'local-combined','pool':'default','endpoint':'http://127.0.0.1:8080','enabled':True,'service-token':accept,'resolve-token':resolve}]}},
  'collector':{
   'storage':{'enabled':True},'datasource':{'url':'jdbc:postgresql://collector-postgres:5432/collector','username':'collector','password':password},
   'http':{'enabled':True,'clients':[{'origin':'rgm','token':accept}]},
   'credentials':{'enabled':True,'ttl':'2h','maximum-size':100,'allow-http':True,'origins':[{'origin':'rgm','endpoint':'http://127.0.0.1:8080','service-token':resolve}]},
   'execution':{'enabled':True},
   'delivery':{'enabled':True,'allow-http':True,'destinations':[{'ref':'rgm-results','origin':'rgm','endpoint':'http://127.0.0.1:8080/internal/v1/collector-results'}]}
  }
 }
 config.write_text(json.dumps(settings,indent=2)+'\n')
if not dbEnv.exists():dbEnv.write_text('POSTGRES_DB=collector\nPOSTGRES_USER=collector\nPOSTGRES_PASSWORD='+password+'\n')
print('Local files prepared. Existing encryption/service keys are preserved and never printed.')
