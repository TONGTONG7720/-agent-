package com.magent.server.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AesUtilTest {

    private final AesUtil aes = new AesUtil("0123456789abcdef");

    @Test
    void encryptThenDecryptRestoresPlaintext() {
        String cipher = aes.encrypt("sk-my-secret-key-001");
        assertThat(cipher).isNotEqualTo("sk-my-secret-key-001");
        assertThat(aes.decrypt(cipher)).isEqualTo("sk-my-secret-key-001");
    }

    @Test
    void encryptTwiceProducesDifferentCipher() {
        // GCM 随机 IV：同明文两次加密结果不同
        assertThat(aes.encrypt("same")).isNotEqualTo(aes.encrypt("same"));
    }

    @Test
    void maskKeepsOnlyLast4() {
        assertThat(AesUtil.mask("sk-abcdef1234")).isEqualTo("sk-***1234");
        assertThat(AesUtil.mask("abc")).isEqualTo("sk-***");
        assertThat(AesUtil.mask(null)).isEqualTo("");
    }
}
