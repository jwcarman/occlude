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
package org.jwcarman.occlude.spring;

import org.jwcarman.occlude.AccessContextProvider;
import org.jwcarman.occlude.Charter;
import org.jwcarman.occlude.DefaultCharter;
import org.jwcarman.occlude.Storage;
import org.jwcarman.occlude.lattice.Axes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * What every backing store needs, whatever it is backed by.
 *
 * <p>Which turns out to be nothing but settings. The store itself is never published: an
 * application needs the charter, to declare what it allows, and the portals, to do the work. It has
 * no use for the thing holding the values -- reading a label or a lineage is an authority in its
 * own right, which is why it is an {@link org.jwcarman.occlude.Inspection} an application declares
 * rather than something every bean could reach.
 */
@AutoConfiguration
@EnableConfigurationProperties(CharterProperties.class)
public class CharterAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(CharterAutoConfiguration.class);

  /**
   * The one reference able to bind, kept by the thing that made it.
   *
   * <p>Not published. The bean below hands out {@link Charter}, which cannot bind, so no
   * application bean can bring its authority into force by naming a type in its constructor. This
   * field is how the binder finds the instance again without the container being able to hand it to
   * anyone else.
   */
  private DefaultCharter constituted;

  /**
   * The charter itself, constructed here rather than by the application.
   *
   * <p>Ownership is the whole point. Whoever constructs a charter holds the thing that can bind it,
   * so the application says what its axes are and receives a charter to declare on, never one it
   * could bring into force. That is the same rule the portals follow: authority arrives because
   * somebody handed it to you.
   *
   * <p>Conditional on the application having said what it asks about every value. Guessing a
   * vocabulary would be the worst thing this could do.
   */
  @Bean
  @ConditionalOnBean(Axes.class)
  @ConditionalOnMissingBean
  public Charter charter(Axes axes) {
    DefaultCharter charter = new DefaultCharter(axes);
    this.constituted = charter;
    return charter;
  }

  /**
   * Binds it, last, to the storage and to where identity comes from.
   *
   * <p>{@link SmartInitializingSingleton} runs once the context has finished creating singletons,
   * which is the first moment every portal has been declared and the last moment a charter can be
   * brought into force before one is used.
   *
   * <p>Identity is where an access comes from, not what this application allows, so it arrives with
   * the storage rather than in the charter the application writes. An application that contributes
   * no {@link AccessContextProvider} has no notion of identity, and every access is nobody in
   * particular.
   */
  @Bean
  public SmartInitializingSingleton charterBinder(
      ObjectProvider<Storage> storage,
      ObjectProvider<AccessContextProvider> access,
      CharterProperties properties) {
    // ObjectProvider rather than Storage: storage is contributed by whichever module is on the
    // classpath, and that auto-configuration runs after this one. A direct dependency would be
    // evaluated before it exists and this bean would silently never match -- which it did, and the
    // symptom was every portal refusing at request time because nothing had brought them into
    // force.
    return () -> {
      if (constituted == null) {
        return;
      }
      Storage bindTo = storage.getIfAvailable();
      if (bindTo == null) {
        throw new IllegalStateException(
            "this application declared a charter but nothing supplies storage for it: add a"
                + " storage module, or contribute a Storage bean");
      }
      constituted.bind(bindTo, access.getIfAvailable(AccessContextProvider::none));
      if (properties.isLogManifest()) {
        log.info("\n{}", constituted.manifest());
      }
    };
  }
}
