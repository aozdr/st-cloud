const fs = require('node:fs');
const path = require('node:path');
const file = path.join(__dirname,'auth-storage.json');
if (fs.existsSync(file)) {
  const outer = JSON.parse(fs.readFileSync(file,'utf8'));
  if (outer['stcloud:auth']) {
    const auth = JSON.parse(outer['stcloud:auth']);
    outer['stcloud:auth'] = JSON.stringify({ ...auth, token:null, refreshToken:null, redacted:true, redactedAt:new Date().toISOString() });
  }
  outer.refreshToken = null; outer.accessToken = null; outer.redacted = true;
  fs.writeFileSync(file,JSON.stringify(outer,null,2));
  console.log(JSON.stringify({ check:'fixture_credentials_redacted', file:'live-runtime/auth-storage.json', redacted:true }));
}
