package wynnvoice.protocol;

public final class Protocol {
    public static final int VERSION = 3;
    public static final int MAX_FRAME_BYTES = 64 * 1024;
    public static final int SERVER_ID_BYTES = 20;
    public static final int SECRET_BYTES = 16;

    private Protocol() {}
}
