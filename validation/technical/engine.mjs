// Synthetic state experiment only. NOT a production semantic or safety validator.
// Assumes fixture-supplied document identity; substring checks do not understand negation.
// No Android adapter, LLM, persistent storage or network.
export class SyntheticStateModel {
  constructor(allowed = []) {
    this.allowed = new Set(allowed);
    this.records = new Map();
    this.queue = [];
    this.writes = [];
  }

  ingest(event) {
    // Must happen before reading text, storing, logging or enqueueing payloads.
    if (!this.allowed.has(event.source)) return 'ignored_source';
    if (event.kind === 'notification_removed') return 'notification_only';
    const { proposal: p, text } = event;
    if (!p || typeof text !== 'string' || !p.documentId) return 'needs_source';
    const key = JSON.stringify([event.source, p.documentId]);
    // Fixture-supplied documentId stands for a separately validated stable ID.
    // Notification key alone does NOT establish semantic document identity.
    if (p.kind === 'cancel') {
      if (!p.evidence || !text.includes(p.evidence) || p.evidence !== '행사 취소') return 'needs_confirmation';
      const existing = this.records.get(key);
      if (!existing) return 'needs_confirmation';
      this.records.set(key, { ...existing, status: 'cancelled' });
      this.queue = this.queue.filter(item => item.key !== key);
      this.writes.push(key);
      return 'cancelled';
    }
    if (p.kind !== 'task' || !p.action || !p.child || !p.due) return 'needs_confirmation';
    for (const field of ['action', 'child', 'due']) {
      if (typeof p[field] !== 'string' || !text.includes(p[field])) return 'needs_confirmation';
    }
    if (!/^\d{4}-\d{2}-\d{2}$/.test(p.due)) return 'needs_confirmation';
    const date = new Date(`${p.due}T00:00:00.000Z`);
    if (Number.isNaN(date.valueOf()) || date.toISOString().slice(0, 10) !== p.due) return 'needs_confirmation';
    const existing = this.records.get(key);
    if (existing?.status === 'complete' || existing?.status === 'cancelled') return 'closed_task_unchanged';
    const record = { source: event.source, action: p.action, child: p.child, due: p.due, status: 'open' };
    if (JSON.stringify(existing) === JSON.stringify(record)) return 'duplicate';
    this.records.set(key, record);
    this.queue = this.queue.filter(item => item.key !== key);
    this.queue.push({ key, source: event.source });
    this.writes.push(key);
    return existing ? 'updated' : 'created';
  }

  complete(source, documentId) {
    const key = JSON.stringify([source, documentId]);
    const current = this.records.get(key);
    if (!current || current.status !== 'open') return 'unchanged';
    this.records.set(key, { ...current, status: 'complete' });
    this.queue = this.queue.filter(item => item.key !== key);
    this.writes.push(key);
    return 'complete';
  }

  revoke(source) {
    this.allowed.delete(source);
    this.queue = this.queue.filter(item => item.source !== source);
    return 'revoked';
  }

  // Represents the last boundary before delivery, not an actual network send.
  drain() {
    const deliverable = this.queue.filter(item => this.allowed.has(item.source));
    this.queue = [];
    return deliverable;
  }
}
