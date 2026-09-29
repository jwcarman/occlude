/*
 * Copyright © 2026 James Carman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jwcarman.occlude.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.CodecException;
import org.jwcarman.codec.crypto.JceDataKeyProvider;

/** No database needed: what happens to every byte a store keeps, on its way to disk and back. */
@DisplayName("What happens to every byte a store keeps")
class StoragePipelineTest {

  private static final byte[] CARD =
      "{\"number\":\"4111111111114821\",\"holder\":\"J CARMAN\"}".getBytes(StandardCharsets.UTF_8);

  private final Codec<byte[]> pipeline = JdbcStorageConfig.pipeline(TestKeys.dataKeys());

  @Test
  @DisplayName("gives back exactly what it was given")
  void round_trips() {
    assertThat(pipeline.decode(pipeline.encode(CARD))).isEqualTo(CARD);
  }

  /** The header is what lets a later pipeline be introduced without rewriting what is stored. */
  @Test
  @DisplayName("says which pipeline wrote it, before anything else")
  void says_which_pipeline_wrote_it() {
    byte[] stored = pipeline.encode(CARD);

    assertThat(stored[0]).isEqualTo((byte) 0xC0);
    assertThat(stored[1]).isEqualTo((byte) 0xDC);
    assertThat(stored[2]).isEqualTo((byte) JdbcStorageConfig.ENVELOPE);
  }

  @Test
  @DisplayName("never writes the plaintext")
  void never_writes_the_plaintext() {
    String stored = new String(pipeline.encode(CARD), StandardCharsets.ISO_8859_1);

    assertThat(stored).doesNotContain("4111111111114821").doesNotContain("CARMAN");
  }

  /** Equal values must not look equal on disk, or the table says which rows hold the same card. */
  @Test
  @DisplayName("writes the same value differently every time")
  void writes_the_same_value_differently_every_time() {
    assertThat(pipeline.encode(CARD)).isNotEqualTo(pipeline.encode(CARD));
  }

  /** No compression: a highly repetitive value costs what a random one of its length does. */
  @Test
  @DisplayName("does not compress, so length says nothing about what a value repeats")
  void does_not_compress() {
    byte[] repetitive = "a".repeat(4096).getBytes(StandardCharsets.UTF_8);

    assertThat(pipeline.encode(repetitive)).hasSizeGreaterThan(repetitive.length);
  }

  @Test
  @DisplayName("cannot be read without the key it was written under")
  void cannot_be_read_without_its_key() {
    byte[] stored = pipeline.encode(CARD);
    Codec<byte[]> somebodyElse = JdbcStorageConfig.pipeline(TestKeys.dataKeys());

    assertThatThrownBy(() -> somebodyElse.decode(stored)).isInstanceOf(CodecException.class);
  }

  /** Rotation is adding a key and making it current: what the old one wrote still reads. */
  @Test
  @DisplayName("still reads what an older key wrote after a newer one takes over")
  void reads_across_a_rotation() {
    SecretKey first = TestKeys.aes256();
    SecretKey second = TestKeys.aes256();
    byte[] underFirst =
        JdbcStorageConfig.pipeline(new JceDataKeyProvider("k1", Map.of("k1", first))).encode(CARD);

    Codec<byte[]> rotated =
        JdbcStorageConfig.pipeline(new JceDataKeyProvider("k2", Map.of("k1", first, "k2", second)));

    assertThat(rotated.decode(underFirst)).isEqualTo(CARD);
  }
}
