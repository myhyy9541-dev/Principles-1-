package com.fursan.lan.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LanAddressPolicyTest {
    @Test
    public void allowsPrivateIpv4RangesAndLoopback() {
        assertTrue(LanAddressPolicy.isPrivateLanAddress("10.0.0.2"));
        assertTrue(LanAddressPolicy.isPrivateLanAddress("192.168.1.4"));
        assertTrue(LanAddressPolicy.isPrivateLanAddress("172.16.4.8"));
        assertTrue(LanAddressPolicy.isPrivateLanAddress("172.31.4.8"));
        assertTrue(LanAddressPolicy.isPrivateLanAddress("100.96.0.2"));
        assertTrue(LanAddressPolicy.isPrivateLanAddress("169.254.10.20"));
        assertTrue(LanAddressPolicy.isPrivateLanAddress("127.0.0.1"));
        assertTrue(LanAddressPolicy.isPrivateLanAddress("localhost"));
    }

    @Test
    public void rejectsPublicMalformedAndOutOfRangeAddresses() {
        assertFalse(LanAddressPolicy.isPrivateLanAddress("8.8.8.8"));
        assertFalse(LanAddressPolicy.isPrivateLanAddress("172.32.0.1"));
        assertFalse(LanAddressPolicy.isPrivateLanAddress("192.168.1.999"));
        assertFalse(LanAddressPolicy.isPrivateLanAddress("192.168.1"));
        assertFalse(LanAddressPolicy.isPrivateLanAddress("fe80::1"));
        assertFalse(LanAddressPolicy.isPrivateLanAddress(null));
    }
}
