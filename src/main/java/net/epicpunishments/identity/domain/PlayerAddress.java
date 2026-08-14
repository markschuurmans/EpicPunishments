package net.epicpunishments.identity.domain;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Objects;

public final class PlayerAddress {
    private static final int IPV4_LENGTH = 4;
    private static final int IPV6_LENGTH = 16;
    private static final byte[] IPV4_MAPPED_PREFIX = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff
    };

    private final byte[] bytes;
    private final AddressFamily family;

    private PlayerAddress(byte[] bytes) {
        this.bytes = normalize(bytes);
        this.family = this.bytes.length == IPV4_LENGTH ? AddressFamily.IPV4 : AddressFamily.IPV6;
    }

    public static PlayerAddress from(InetAddress address) {
        Objects.requireNonNull(address, "address");
        return new PlayerAddress(address.getAddress());
    }

    public static PlayerAddress fromBytes(byte[] bytes) {
        return new PlayerAddress(Objects.requireNonNull(bytes, "bytes"));
    }

    public AddressFamily family() {
        return family;
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    /**
     * Returns the complete address. Callers must enforce the dedicated IP-view permission before displaying it.
     */
    public String fullAddress() {
        try {
            return InetAddress.getByAddress(bytes).getHostAddress();
        } catch (UnknownHostException impossible) {
            throw new IllegalStateException("A validated address had an invalid length", impossible);
        }
    }

    public String redacted() {
        if (family == AddressFamily.IPV4) {
            return Byte.toUnsignedInt(bytes[0]) + "." + Byte.toUnsignedInt(bytes[1]) + "."
                    + Byte.toUnsignedInt(bytes[2]) + ".x";
        }
        int first = (Byte.toUnsignedInt(bytes[0]) << 8) | Byte.toUnsignedInt(bytes[1]);
        int second = (Byte.toUnsignedInt(bytes[2]) << 8) | Byte.toUnsignedInt(bytes[3]);
        return Integer.toHexString(first) + ':' + Integer.toHexString(second) + ":…";
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof PlayerAddress that && Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return redacted();
    }

    private static byte[] normalize(byte[] source) {
        if (source.length == IPV4_LENGTH) {
            return source.clone();
        }
        if (source.length != IPV6_LENGTH) {
            throw new IllegalArgumentException("An IP address must contain 4 or 16 bytes");
        }
        boolean mapped = true;
        for (int index = 0; index < IPV4_MAPPED_PREFIX.length; index++) {
            if (source[index] != IPV4_MAPPED_PREFIX[index]) {
                mapped = false;
                break;
            }
        }
        return mapped ? Arrays.copyOfRange(source, IPV4_MAPPED_PREFIX.length, IPV6_LENGTH) : source.clone();
    }
}
