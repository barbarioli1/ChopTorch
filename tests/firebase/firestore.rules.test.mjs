import fs from 'node:fs';
import { after, before, test } from 'node:test';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, setDoc, getDoc, deleteDoc } from 'firebase/firestore';

const projectId = 'demo-choptorch-diagnostics';
let environment;

before(async () => {
  environment = await initializeTestEnvironment({
    projectId,
    firestore: {
      rules: fs.readFileSync(new URL('../../firestore.rules', import.meta.url), 'utf8')
    }
  });
});

after(async () => {
  await environment.cleanup();
});

function event(eventId, extra = {}) {
  return {
    schemaVersion: 1,
    eventId,
    kind: 'gesture_attempt',
    createdAtEpochMs: 1794520000000,
    ...extra
  };
}

function eventRef(uid, eventId, db = environment.authenticatedContext(uid).firestore()) {
  return doc(db, `diagnosticEvents/${uid}/events/${eventId}`);
}

test('owner can create and retry identical content only', async () => {
  const uid = 'tester-a';
  const id = 'a4d3ab9c-9a9b-4ce0-a0cf-608b62c87f31';
  const ref = eventRef(uid, id);
  const value = event(id);
  await assertSucceeds(setDoc(ref, value));
  await assertSucceeds(setDoc(ref, value));
  await assertFails(setDoc(ref, { ...value, kind: 'torch_error' }));
  await assertFails(getDoc(ref));
  await assertFails(deleteDoc(ref));
});

test('rejects unauthenticated, cross-user, malformed and unknown-field writes', async () => {
  const id = 'a4d3ab9c-9a9b-4ce0-a0cf-608b62c87f31';
  const unauthenticated = environment.unauthenticatedContext().firestore();
  await assertFails(setDoc(eventRef('tester-a', id, unauthenticated), event(id)));
  const tester = environment.authenticatedContext('tester-a').firestore();
  await assertFails(setDoc(eventRef('tester-b', id, tester), event(id)));
  await assertFails(setDoc(eventRef('tester-a', 'not-a-uuid'), event('not-a-uuid')));
  await assertFails(setDoc(eventRef('tester-a', id), event(id, { unexpected: true })));
  await assertFails(setDoc(eventRef('tester-a', id), event(id, { kind: 'arbitrary' })));
  await assertFails(setDoc(eventRef('tester-a', id), event(id, { torchOutcome: 'maybe' })));
  await assertFails(setDoc(eventRef('tester-a', id), event(id, { maxScore: 1.5 })));
  await assertFails(setDoc(eventRef('tester-a', id), event(id, { predictionCount: 101 })));
});

test('denies unrelated collection paths', async () => {
  const db = environment.authenticatedContext('tester-a').firestore();
  await assertFails(setDoc(doc(db, 'otherCollection/document'), { value: true }));
});
