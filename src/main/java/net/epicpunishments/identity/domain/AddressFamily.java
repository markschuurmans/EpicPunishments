package net.epicpunishments.identity.domain;

public enum AddressFamily {
    IPV4(4),
    IPV6(16);

    private final int byteLength;

    AddressFamily(int byteLength) {
        this.byteLength = byteLength;
    }

    public int byteLength() {
        return byteLength;
    }
}
