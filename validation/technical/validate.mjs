import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import { SyntheticStateModel } from './engine.mjs';

const fixtures = JSON.parse(readFileSync(new URL('./fixtures.json', import.meta.url), 'utf8'));
const results = [];
function check(name, run) {
  try { run(); results.push({ name, pass: true }); }
  catch (error) { results.push({ name, pass: false, error: error.message }); }
}

check('identical observed payloads can correspond to incompatible hidden documents', () => {
  const pair = fixtures.observationalCounterexample;
  assert.deepEqual(pair.notificationA, pair.notificationB);
  assert.notEqual(pair.documentA.requiredAction, pair.documentB.requiredAction);
  assert.notEqual(pair.documentA.due, pair.documentB.due);
  // Any function of only an identical input has no discriminating evidence.
  // This is a constructed counterexample, not a claim about either school app.
});

for (const fixture of fixtures.cases) check(fixture.name, () => {
  const gate = new SyntheticStateModel(['synthetic.school', 'synthetic.otherSchool']);
  const actual = fixture.operations.map(operation => {
    if (operation.type === 'ingest') return gate.ingest({ ...structuredClone(fixtures.baseEvent), ...operation.patch });
    if (operation.type === 'complete') return gate.complete('synthetic.school', 'doc-1');
    if (operation.type === 'revoke') return gate.revoke('synthetic.school');
    throw new Error(`unknown operation ${operation.type}`);
  });
  assert.deepEqual(actual, fixture.results);
  assert.equal(gate.records.size, fixture.records);
  assert.equal(gate.queue.length, fixture.queue);
  assert.equal(gate.writes.length, fixture.writes);
  if (fixture.status) assert.equal([...gate.records.values()][0].status, fixture.status);
  if (fixture.due) assert.equal([...gate.records.values()][0].due, fixture.due);
});

check('unselected raw text is never inspected', () => {
  const gate = new SyntheticStateModel(['synthetic.school']);
  const event = { source: 'synthetic.bank', get text() { throw new Error('private payload accessed'); } };
  assert.equal(gate.ingest(event), 'ignored_source');
  assert.equal(gate.writes.length, 0);
});

check('final delivery boundary rejects stale revoked-source queue entry', () => {
  const gate = new SyntheticStateModel(['synthetic.school']);
  gate.ingest(structuredClone(fixtures.baseEvent));
  gate.allowed.delete('synthetic.school'); // Simulate stale queue/race before final boundary.
  assert.deepEqual(gate.drain(), []);
});

const report = {
  generatedAt: new Date().toISOString(), runtime: process.version,
  scope: 'Synthetic rule/state unit tests only; no Android, school-source payload, LLM, user or business validation.',
  counterexample: 'Two incompatible hidden documents share identical observed notification payload. Notification-only input cannot uniquely recover the hidden document.',
  passed: results.filter(result => result.pass).length, total: results.length, results,
};
writeFileSync(new URL('./results.json', import.meta.url), JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify(report, null, 2));
if (report.passed !== report.total) process.exitCode = 1;
