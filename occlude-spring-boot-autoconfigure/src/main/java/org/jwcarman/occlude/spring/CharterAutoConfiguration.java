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

import io.micrometer.observation.ObservationRegistry;
import org.jwcarman.occlude.AccessContextProvider;
import org.jwcarman.occlude.Bindings;
import org.jwcarman.occlude.Charter;
import org.jwcarman.occlude.DefaultCharter;
import org.jwcarman.occlude.lattice.Axes;
import org.jwcarman.occlude.storage.Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.ContextRefreshedEvent;

/**
 * What every backing store needs, whatever it is backed by.
 *
 * <p>Which turns out to be nothing but settings, and one rule about the store. An application needs
 * the charter, to declare what it allows, and the portals, to do the work. It has no use for the
 * thing holding the values, which can hand over any of them decrypted with no ceiling asked and no
 * line written. So the store is found by the name {@value #STORAGE}, and a store module registers
 * it as no candidate for injection by type: a bean that asks for a {@link Storage} in its
 * constructor gets none, where otherwise it would quietly have had every value there is.
 *
 * <p>An application supplying its own store names its bean {@value #STORAGE}, and should register
 * it the same way: {@code @Bean(name = CharterAutoConfiguration.STORAGE, defaultCandidate =
 * false)}.
 */
@AutoConfiguration(after = ObservationAutoConfiguration.class)
@EnableConfigurationProperties(CharterProperties.class)
public class CharterAutoConfiguration {

  /** Created by Spring Boot's auto-configuration. */
  public CharterAutoConfiguration() {
    // The charter and its binding are the beans below.
  }

  private static final Logger log = LoggerFactory.getLogger(CharterAutoConfiguration.class);

  /** The name a charter's store is registered, and found, under. */
  public static final String STORAGE = "occludeStorage";

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
   *
   * @param axes what the application asks about every value
   * @return the charter, not yet bound
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
   * the storage rather than in the charter the application writes.
   *
   * <p>Storage is required, not looked for: an application that declared a charter and supplies
   * none fails to start, with Spring's own report of the missing bean, rather than serving requests
   * that all refuse. It is asked for by name, which is what finds a store no other bean can.
   *
   * <p>Every operation is observed through Spring Boot's {@link ObservationRegistry}, which is
   * always there: this module depends on Boot's observation auto-configuration and runs after it,
   * as Boot's own observation auto-configurations do. With no handler it records nothing; Actuator
   * or a tracing bridge adds one, and every operation shows up.
   *
   * @param storage the charter's store, found by name
   * @param access where identity comes from
   * @param observations the registry every operation is observed through
   * @param properties the starter's configuration
   * @return the initializer that binds the charter once the context is ready
   */
  @Bean
  @ConditionalOnBean(Axes.class)
  public SmartInitializingSingleton charterBinder(
      @Qualifier(STORAGE) Storage storage,
      AccessContextProvider access,
      ObservationRegistry observations,
      CharterProperties properties) {
    return () -> {
      if (constituted == null) {
        return;
      }
      constituted.bind(Bindings.of(storage).withIdentity(access).observedBy(observations));
      if (properties.isLogManifest()) {
        log.info("\n{}", constituted.manifest());
      }
    };
  }

  /**
   * Refuses to start when the application supplied its own charter and never bound it.
   *
   * <p>A charter the application constructs is the application's to bind -- that is the rule this
   * starter follows for its own -- so it is left alone. But left unbound, every portal refused at
   * its first use, long after startup said all was well. Once the context has refreshed, every
   * singleton has had its chance to bind it; one still unbound is a mistake, and said so now.
   *
   * @param charter the application's charter
   * @return the listener that makes the check once the context has refreshed
   */
  @Bean
  @ConditionalOnBean(Charter.class)
  public ApplicationListener<ContextRefreshedEvent> unboundCharterCheck(Charter charter) {
    return event -> {
      if (charter != constituted && charter instanceof DefaultCharter own && !own.isBound()) {
        throw new IllegalStateException(
            "this application supplies its own Charter bean and never bound it, so none of its"
                + " portals would ever work. Bind it once everything is declared --"
                + " charter.bind(Bindings.of(storage).withIdentity(...)) -- or remove the bean and"
                + " declare an Axes bean, and the starter will construct the charter and bind it.");
      }
    };
  }

  /**
   * Nobody in particular, for an application with no notion of identity. Any {@link
   * AccessContextProvider} the application contributes replaces it.
   *
   * <p>Said out loud, because a charter's rule is that no identity is something an application
   * declares rather than gets by forgetting: a ceiling written as "unless the context says
   * otherwise" is wider than it looks when the context is always empty.
   *
   * @return a provider whose context is always empty
   */
  @Bean
  @ConditionalOnBean(Axes.class)
  @ConditionalOnMissingBean
  public AccessContextProvider accessContextProvider() {
    log.warn(
        "No AccessContextProvider bean, so every access is bound with an empty context. Contribute"
            + " one if any ceiling or policy depends on who is asking.");
    return AccessContextProvider.none();
  }
}
