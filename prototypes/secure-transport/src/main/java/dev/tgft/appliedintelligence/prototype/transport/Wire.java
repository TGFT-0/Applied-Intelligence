package dev.tgft.appliedintelligence.prototype.transport;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.security.*;
import java.util.*;

final class Wire {
  static final ObjectMapper JSON =
      JsonMapper.builder(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(8)
                          .maxStringLength(32768)
                          .maxNumberLength(20)
                          .maxDocumentLength(262144)
                          .build())
                  .build())
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .enable(
              DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
              DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  static byte[] bytes(Object value) throws IOException {
    return JSON.writeValueAsBytes(value);
  }

  static <T> T read(byte[] value, Class<T> type) throws IOException {
    return JSON.readValue(value, type);
  }

  static String hash(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static String random() {
    byte[] b = new byte[32];
    new SecureRandom().nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  static boolean same(String a, String b) {
    return MessageDigest.isEqual(
        a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  static void uuid(String id) {
    if (id == null || !UUID.fromString(id).toString().equals(id))
      throw new Fault(400, "invalid_id");
  }

  static final class Fault extends RuntimeException {
    final int status;
    final String code;

    Fault(int status, String code) {
      super(code);
      this.status = status;
      this.code = code;
    }
  }

  record Error(String error) {}
}
