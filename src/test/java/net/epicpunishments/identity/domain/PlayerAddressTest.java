package net.epicpunishments.identity.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlayerAddressTest {
    @Test
    void normalizesIpv4MappedIpv6AndCopiesInput() {
        byte[] mapped = {
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff,
                (byte) 192, 0, 2, 42
        };

        PlayerAddress address = PlayerAddress.fromBytes(mapped);
        mapped[12] = 1;
        byte[] returned = address.bytes();
        returned[0] = 1;

        assertThat(address.family()).isEqualTo(AddressFamily.IPV4);
        assertThat(address.bytes()).containsExactly((byte) 192, (byte) 0, (byte) 2, (byte) 42);
        assertThat(address).isEqualTo(PlayerAddress.fromBytes(new byte[]{(byte) 192, 0, 2, 42}));
    }

    @Test
    void redactsAddressesByDefault() {
        PlayerAddress ipv4 = PlayerAddress.fromBytes(new byte[]{(byte) 203, 0, 113, 25});
        PlayerAddress ipv6 = PlayerAddress.fromBytes(new byte[]{
                0x20, 0x01, 0x0d, (byte) 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1
        });

        assertThat(ipv4.toString()).isEqualTo("203.0.113.x");
        assertThat(ipv4.fullAddress()).isEqualTo("203.0.113.25");
        assertThat(ipv6.toString()).isEqualTo("2001:db8:…");
        assertThat(ipv6.toString()).doesNotContain("0:0:0:0:0:0:1");
    }

    @Test
    void rejectsNonAddressByteLengths() {
        assertThatThrownBy(() -> PlayerAddress.fromBytes(new byte[8]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("4 or 16 bytes");
    }
}
