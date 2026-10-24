export type Overview = {
  condominiumId: string;
  name: string;
  queuedCalls: number;
  assignedCalls: number;
  openIncidents: number;
  queuedPanics: number;
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

export type Unit = { id: string; blockId: string; number: string };
export type Parcel = { id: string; unitId: string; carrier: string | null; description: string | null; status: string; receivedAt: string; pickedUpAt: string | null; pickedUpByName: string | null };
export type Visitor = { id: string; unitId: string; fullName: string; kind: string };
export type Attempt = { id: string; credentialId: string | null; entrance: string; result: string; reason: string; occurredAt: string };
export type DeviceFull = Device & { adapter: string; lastSeenAt: string | null; residentOpenable?: boolean };
export type CommandRow = { id: string; deviceId: string; state: string; failureReason: string | null; requestedAt: string };

export type VisitRequest = { id: string; unitId: string; callId: string | null; visitorName: string; kind: string; status: string; createdAt: string; expiresAt: string; decidedAt: string | null };

export type AccessRow = { attemptId: string; occurredAt: string; entrance: string; result: string; reason: string; unitId: string | null; unitLabel: string | null; visitorName: string | null; exitedAt: string | null };
export type Announcement = { id: string; title: string; body: string; createdAt: string };
export type ShiftLogEntry = { id: string; shiftId: string | null; kind: string; text: string; createdAt: string };
