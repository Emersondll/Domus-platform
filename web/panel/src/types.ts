export type Overview = {
  condominiumId: string;
  name: string;
  queuedCalls: number;
  assignedCalls: number;
  openIncidents: number;
  deviceHealth: string;
  connectivity: string;
};

export type Call = {
  id: string;
  condominiumId: string;
  unitId: string | null;
  source: string;
  callerName: string | null;
  status: string;
  assignedUserId: string | null;
  createdAt: string;
  outcome: string | null;
};

export type CallEvent = { type: string; detail: string | null; actorUserId: string | null; occurredAt: string };
export type Incident = {
  id: string;
  severity: string;
  title: string;
  description: string | null;
  status: string;
  openedAt: string;
};
export type OnDuty = { id: string; displayName: string };
export type Shift = { id: string; startedAt: string; endedAt: string | null };

export const SENSITIVE_OUTCOMES = ["ENTRY_GRANTED", "ENTRY_DENIED"];
export const OUTCOMES = ["RESOLVED", "ENTRY_GRANTED", "ENTRY_DENIED", "NO_ANSWER", "FALSE_ALARM"];

export type Device = { id: string; name: string; kind: string; entrance: string; status: string };
export type Command = { id: string; state: string; failureReason: string | null; evidence: string | null };
