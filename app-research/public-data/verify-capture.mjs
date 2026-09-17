// Offline checks of the captured public API evidence; no HTTP and no app code.
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
const read = file => readFileSync(new URL(file, import.meta.url), 'utf8');
const initial = JSON.parse(read('./probe-result.json'));
const report = JSON.parse(read('./school-event-result.json'));
const raw = read('./SchoolSchedule-response.json');
const parsed = JSON.parse(raw);
const rows = parsed.SchoolSchedule.find(part => part.row).row;
const checks = [];
function check(name, fn) { try { fn(); checks.push({ name, pass: true }); } catch(error) { checks.push({ name, pass: false, error: error.message }); } }
check('HTTP 200 alone was not treated as API success', () => assert.equal(initial.head.CODE, 'ERROR-300'));
check('successful capture matches recorded content hash', () => assert.equal(createHash('sha256').update(raw).digest('hex'), report.attempts[1].sha256));
check('official sample has INFO-000 and five rows', () => { assert.equal(parsed.SchoolSchedule[0].head[1].RESULT.CODE, 'INFO-000'); assert.equal(rows.length, 5); });
check('parsed output corresponds to captured source dates and names', () => assert.deepEqual(report.events.map(event => [event.date,event.event]), rows.map(row => [row.AA_YMD,row.EVENT_NM])));
check('all five captured rows are historical as of capture date', () => assert.ok(rows.every(row => row.AA_YMD < '20260913')));
check('sample has no explicit individual consent or payment completion fields', () => assert.ok(rows.every(row => !Object.keys(row).some(key => /PAYMENT|CONSENT|SUBMIT|STUDENT_NAME/i.test(key)))));
check('both successful reads used no credentials', () => assert.ok(report.attempts.every(attempt => !attempt.credentialUsed && !/[?&]key=/i.test(attempt.url))));
const output = { checkedAt: new Date().toISOString(), label:'Offline evidence integrity and scope checks only; no user or product validation.', passed:checks.filter(check=>check.pass).length,total:checks.length,checks };
writeFileSync(new URL('./verification.json',import.meta.url),JSON.stringify(output,null,2)+'\n');
console.log(JSON.stringify(output,null,2));
if(output.passed!==output.total) process.exitCode=1;
