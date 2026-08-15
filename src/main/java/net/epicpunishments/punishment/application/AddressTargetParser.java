package net.epicpunishments.punishment.application;

import net.epicpunishments.identity.domain.PlayerAddress;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;

public final class AddressTargetParser {
    public PlayerAddress parse(String input) {
        if (input == null || !input.toLowerCase(Locale.ROOT).startsWith("ip:")) {
            throw new IllegalArgumentException("Use an explicit IP target such as ip:192.0.2.1 or ip:2001:db8::1");
        }
        String literal = input.substring(3);
        boolean ipv4 = literal.indexOf(':') < 0 && literal.matches("[0-9]+(?:\\.[0-9]+){3}");
        boolean ipv6 = literal.indexOf(':') >= 0 && literal.matches("[0-9A-Fa-f:.]+");
        if (!ipv4 && !ipv6) {
            throw new IllegalArgumentException("The IP target must contain a numeric IPv4 or IPv6 address");
        }
        if (ipv4) {
            String[] parts = literal.split("\\.", -1);
            byte[] bytes = new byte[4];
            for (int index = 0; index < parts.length; index++) {
                int value;
                try {
                    value = Integer.parseInt(parts[index]);
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException("The IP target is invalid", exception);
                }
                if (value > 255) {
                    throw new IllegalArgumentException("The IP target is invalid");
                }
                bytes[index] = (byte) value;
            }
            return PlayerAddress.fromBytes(bytes);
        }
        try {
            return PlayerAddress.from(InetAddress.getByName(literal));
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("The IP target is invalid", exception);
        }
    }
}
