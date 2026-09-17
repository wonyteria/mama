// Read-only, one-request public-data experiment. Not product application code.
import { writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
const url = 'https://open.neis.go.kr/hub/SchoolSchedule?Type=json&pIndex=1&pSize=5';
const report = { requestedAt: new Date().toISOString(), url, credentialUsed: false, requestCount: 1 };
try {
  const response = await fetch(url, { signal: AbortSignal.timeout(20000), redirect: 'error' });
  report.status = response.status;
  report.contentType = response.headers.get('content-type');
  const raw = await response.text();
  if (raw.length > 100000) throw new Error('Unexpectedly large response; not persisted.');
  report.sha256 = createHash('sha256').update(raw).digest('hex');
  report.responseBytes = Buffer.byteLength(raw);
  writeFileSync(new URL('./response.txt', import.meta.url), raw);
  try {
    const parsed = JSON.parse(raw);
    const rows = parsed.SchoolSchedule?.find(part => Array.isArray(part.row))?.row ?? [];
    const head = parsed.SchoolSchedule?.find(part => Array.isArray(part.head))?.head;
    report.head = head ?? parsed.RESULT ?? parsed;
    report.rowsReceived = rows.length;
    report.fieldsReceived = rows.length ? Object.keys(rows[0]) : [];
    const todayKst = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date()).replaceAll('-', '');
    report.todayKst = todayKst;
    report.sample = rows.slice(0, 5).map(row => ({ school: row.SCHUL_NM, schoolCode: row.SD_SCHUL_CODE, officeCode: row.ATPT_OFCDC_SC_CODE, schoolYear: row.AY, date: row.AA_YMD, event: row.EVENT_NM, description: row.EVENT_CNTNT, schoolLevel: row.SCHUL_CRSE_SC_NM }));
    report.upcomingRowsInSample = rows.filter(row => typeof row.AA_YMD === 'string' && row.AA_YMD >= todayKst).length;
    report.privateCompletionFieldsObserved = report.fieldsReceived.filter(key => /PAYMENT|CONSENT|SUBMIT|STUDENT_NAME/i.test(key));
    report.apiJsonParsed = true;
  } catch (error) { report.apiJsonParsed = false; report.parseError = error.message; }
} catch (error) {
  report.fetchError = error.message;
  report.fetchCause = error.cause?.code ?? error.cause?.message ?? null;
}
report.completedAt = new Date().toISOString();
writeFileSync(new URL('./probe-result.json', import.meta.url), JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify(report, null, 2));
