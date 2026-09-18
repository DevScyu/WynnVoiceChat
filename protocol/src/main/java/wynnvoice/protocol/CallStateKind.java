package wynnvoice.protocol;

/** Progress of a friend call as seen by one side; the refusals after {@code ACTIVE} only reach the caller. */
public enum CallStateKind {
    RINGING, INCOMING, ACTIVE, DECLINED, NO_ANSWER, ENDED, BUSY, DND
}
