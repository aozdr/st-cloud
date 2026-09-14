import assert from 'node:assert/strict';
import test from 'node:test';
import { RELAY_RESTART_REQUIRED, resolveUploadResumePlan } from './upload-resume';

test('relay 恢复不会降级为普通直传', () => {
  assert.equal(resolveUploadResumePlan({ transferMode: 'relay', error: null }), 'relay');
  assert.equal(resolveUploadResumePlan({ transferMode: 'relay', error: RELAY_RESTART_REQUIRED }), 'restart-required');
  assert.equal(resolveUploadResumePlan({ transferMode: 'direct', error: null }), 'direct');
});
