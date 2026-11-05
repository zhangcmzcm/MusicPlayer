package org.example.music.user.domain;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Base64;
import org.junit.jupiter.api.Test;
class PasswordResetIdentifiersTest {
 @Test void should_generate_canonical_random_identifiers_and_safe_digests() {
  String first=PasswordResetIdentifiers.generate(),second=PasswordResetIdentifiers.generate();
  assertEquals(43,first.length()); assertEquals(32,Base64.getUrlDecoder().decode(first).length);
  assertNotEquals(first,second); assertTrue(PasswordResetIdentifiers.isCanonicalDigest(first));
  assertFalse(PasswordResetIdentifiers.isCanonicalDigest("A".repeat(42)+"B"));
  assertFalse(PasswordResetIdentifiers.isCanonicalDigest(null));
  assertNotEquals(PasswordResetIdentifiers.digest(first),PasswordResetIdentifiers.digest(second));
 }
}
