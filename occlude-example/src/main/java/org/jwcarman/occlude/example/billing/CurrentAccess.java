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
package org.jwcarman.occlude.example.billing;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jwcarman.occlude.AccessContext;
import org.jwcarman.occlude.AccessContextProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Who is asking, as this service knows it.
 *
 * <p>Read from the request the container is already holding, which is why no service method in this
 * application takes an {@code AccessContext} parameter. Identity is known at the edge and needed at
 * the gate several layers down, and threading it through everything in between is how a safety
 * feature becomes the most annoying thing in a codebase.
 *
 * <p>Headers here because the example has no identity provider. A real service would read {@code
 * SecurityContextHolder}; the charter never learns the difference, because it is handed a supplier
 * and asks it.
 *
 * <p>The roles never reach the context. A ceiling asks what a person may do, so this provider turns
 * the roles into capabilities, such as {@link #APPROVES_DISPUTES}. It is the one place that knows
 * which roles grant which capability, and a person with several roles loses none of them.
 */
@Component
public final class CurrentAccess implements AccessContextProvider {

  /** The capability to see what an approval needs, and to truncate a card for it. */
  public static final String APPROVES_DISPUTES = "approves-disputes";

  /** The roles that grant {@link #APPROVES_DISPUTES}. */
  private static final Set<String> APPROVERS = Set.of("approver");

  /** Creates the provider, which Spring instantiates. */
  public CurrentAccess() {}

  @Override
  public AccessContext get() {
    if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
      return AccessContext.empty();
    }
    HttpServletRequest request = attrs.getRequest();
    Map<String, String> who = new LinkedHashMap<>();
    put(who, "tenant", request.getHeader("X-Tenant"));
    put(who, "principal", request.getHeader("X-User"));
    if (roles(request.getHeader("X-Roles")).stream().anyMatch(APPROVERS::contains)) {
      who.put(APPROVES_DISPUTES, "true");
    }
    return AccessContext.of(who);
  }

  /** A comma-separated list, as an identity provider's roles would arrive here. */
  private static List<String> roles(String header) {
    if (header == null) {
      return List.of();
    }
    return Arrays.stream(header.split(",")).map(String::trim).filter(r -> !r.isEmpty()).toList();
  }

  private static void put(Map<String, String> who, String key, String value) {
    if (value != null && !value.isBlank()) {
      who.put(key, value);
    }
  }
}
