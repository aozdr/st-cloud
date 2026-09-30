const { spawn } = require('node:child_process');
const crypto = require('node:crypto');
function session() {
  const process = spawn('docker', ['exec', '-i', 'st-review-mysql-20260930', 'mysql', '-uroot',
    '-p' + process.env.STCLOUD_TEST_MYSQL_PASSWORD, '--batch', '--raw', '--skip-column-names', '--unbuffered', 'review_role'], { windowsHide: true });
  let output = '', pending;
  process.stdout.on('data', bytes => {
    output += bytes.toString();
    if (pending && output.includes(pending.marker)) {
      const query = pending; pending = null;
      query.resolve(output.slice(query.start).replace(query.marker, '').trim());
    }
  });
  process.stderr.on('data', () => {});
  process.on('error', error => { if (pending) pending.reject(error); });
  return {
    async query(sql) {
      const marker = 'END_' + crypto.randomBytes(8).toString('hex');
      const result = new Promise((resolve, reject) => { pending = { marker, resolve, reject, start: output.length }; });
      process.stdin.write(sql + "; SELECT '" + marker + "';\n");
      return result;
    },
    close() { process.stdin.end(); },
  };
}
(async () => {
  const a = session(), b = session();
  const prefix = 'r_' + crypto.randomBytes(4).toString('hex') + '_';
  const space = prefix + 'space', role = prefix + 'role', member = prefix + 'member';
  const tenant = prefix + 'tenant', user = prefix + 'user', permission = prefix + 'permission';
  try {
    console.log('isolation=' + await a.query('SELECT @@transaction_isolation'));
    await a.query(`CREATE TABLE ${space}(id BIGINT PRIMARY KEY) ENGINE=InnoDB;
      CREATE TABLE ${role}(id BIGINT PRIMARY KEY,space_id BIGINT,deleted INT DEFAULT 0) ENGINE=InnoDB;
      CREATE TABLE ${member}(id BIGINT PRIMARY KEY,space_id BIGINT,role BIGINT,deleted INT DEFAULT 0) ENGINE=InnoDB;
      INSERT INTO ${space} VALUES(1); INSERT INTO ${role}(id,space_id) VALUES(3,1); INSERT INTO ${member} VALUES(1,1,0,0)`);
    await a.query(`BEGIN; SELECT id FROM ${space} WHERE id=1 FOR UPDATE`);
    await b.query(`BEGIN; SELECT role FROM ${member} WHERE space_id=1 AND id=1 AND deleted=0`);
    const spaceLock = b.query(`SELECT id FROM ${space} WHERE id=1 FOR UPDATE`);
    await a.query(`INSERT INTO ${member} VALUES(2,1,3,0); COMMIT`);
    await spaceLock;
    console.log('delete_snapshot_reference_count=' + await b.query(`SELECT COUNT(*) FROM ${member} WHERE space_id=1 AND role=3 AND deleted=0`));
    await b.query(`UPDATE ${role} SET deleted=1 WHERE id=3; COMMIT`);
    console.log('committed_dangling_references=' + await a.query(`SELECT COUNT(*) FROM ${member} m JOIN ${role} r ON r.id=m.role WHERE r.deleted=1 AND m.deleted=0`));

    await a.query(`CREATE TABLE ${tenant}(id BIGINT PRIMARY KEY,status INT) ENGINE=InnoDB;
      CREATE TABLE ${user}(id BIGINT PRIMARY KEY,security_version BIGINT) ENGINE=InnoDB;
      CREATE TABLE ${permission}(id BIGINT PRIMARY KEY,code VARCHAR(50),deleted INT DEFAULT 0) ENGINE=InnoDB;
      INSERT INTO ${tenant} VALUES(1,1); INSERT INTO ${permission} VALUES(1,'file:delete',0)`);
    await a.query(`BEGIN; SELECT id FROM ${tenant} WHERE id=1 FOR UPDATE`);
    await b.query(`BEGIN; SELECT COUNT(*) FROM ${user}; SELECT id FROM ${tenant} WHERE id=1`);
    const tenantLock = b.query(`SELECT id FROM ${tenant} WHERE id=1 FOR UPDATE`);
    await a.query(`UPDATE ${permission} SET deleted=1 WHERE id=1; UPDATE ${user} SET security_version=security_version+1; COMMIT`);
    await tenantLock;
    await b.query(`INSERT INTO ${user} VALUES(7,0)`);
    console.log('registration_snapshot_permissions=' + await b.query(`SELECT code FROM ${permission} WHERE deleted=0`));
    await b.query('COMMIT');
    console.log('current_effective_permission_count=' + await a.query(`SELECT COUNT(*) FROM ${permission} WHERE deleted=0`));
    console.log('new_user_current_security_version=' + await a.query(`SELECT security_version FROM ${user} WHERE id=7`));
  } finally { a.close(); b.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
