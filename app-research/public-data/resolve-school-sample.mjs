// Bounded public-data experiment: at most two keyless GETs, no account access.
import { writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
const attempts = [];
async function getSample(name, url) {
  const record = { name, url, requestedAt: new Date().toISOString(), credentialUsed: false };
  attempts.push(record);
  try {
    const response = await fetch(url, { signal: AbortSignal.timeout(20000), redirect: 'error' });
    record.status = response.status;
    const raw = await response.text();
    if (raw.length > 100000) throw new Error('Unexpectedly large response; not persisted');
    record.sha256 = createHash('sha256').update(raw).digest('hex');
    record.bytes = Buffer.byteLength(raw);
    writeFileSync(new URL(`./${name}-response.json`, import.meta.url), raw);
    const parsed = JSON.parse(raw);
    record.result = parsed.RESULT ?? parsed[name]?.find(part => part.head)?.head;
    const rows = parsed[name]?.find(part => part.row)?.row ?? [];
    record.rowsReceived = rows.length;
    return rows;
  } catch (error) { record.error = error.message; record.cause = error.cause?.code ?? null; return []; }
  finally { record.completedAt = new Date().toISOString(); }
}
const schools = await getSample('schoolInfo', 'https://open.neis.go.kr/hub/schoolInfo?Type=json&pIndex=1&pSize=5');
const school = schools[0];
let events = [];
if (school?.ATPT_OFCDC_SC_CODE && school?.SD_SCHUL_CODE) {
  const query = new URLSearchParams({ Type: 'json', pIndex: '1', pSize: '5', ATPT_OFCDC_SC_CODE: school.ATPT_OFCDC_SC_CODE, SD_SCHUL_CODE: school.SD_SCHUL_CODE });
  events = await getSample('SchoolSchedule', `https://open.neis.go.kr/hub/SchoolSchedule?${query}`);
}
const report = {
  label: 'Actual public API response experiment, not synthetic fixtures or product app.',
  attempts,
  selectedSchool: school ? { name: school.SCHUL_NM, officeCode: school.ATPT_OFCDC_SC_CODE, schoolCode: school.SD_SCHUL_CODE } : null,
  observedFields: events[0] ? Object.keys(events[0]) : [],
  events: events.map(row => ({ school: row.SCHUL_NM, schoolYear: row.AY, date: row.AA_YMD, event: row.EVENT_NM, description: row.EVENT_CNTNT, grades: [row.ONE_GRADE_EVENT_YN, row.TW_GRADE_EVENT_YN, row.THREE_GRADE_EVENT_YN, row.FR_GRADE_EVENT_YN, row.FIV_GRADE_EVENT_YN, row.SIX_GRADE_EVENT_YN] })),
};
writeFileSync(new URL('./school-event-result.json', import.meta.url), JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify(report, null, 2));
