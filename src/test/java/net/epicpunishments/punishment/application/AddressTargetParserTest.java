package net.epicpunishments.punishment.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AddressTargetParserTest {
    private final AddressTargetParser parser = new AddressTargetParser();

    @Test
    void acceptsNumericIpv4AndIpv6AndNormalizesMappedAddresses() {
        assertThat(parser.parse("ip:192.0.2.25").fullAddress()).isEqualTo("192.0.2.25");
        assertThat(parser.parse("ip:::ffff:192.0.2.25").fullAddress()).isEqualTo("192.0.2.25");
        assertThat(parser.parse("ip:2001:db8::1").fullAddress()).contains("2001:db8");
    }

    @Test
    void rejectsHostnamesAndMissingTypedPrefixWithoutResolvingThem() {
        assertThatThrownBy(() -> parser.parse("ip:example.com")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("192.0.2.25")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("ip:999.0.0.1")).isInstanceOf(IllegalArgumentException.class);
    }
}
