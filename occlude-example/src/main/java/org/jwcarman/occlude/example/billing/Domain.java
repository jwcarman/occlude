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

import java.math.BigDecimal;
import org.jwcarman.occlude.OccludedType;

/** The things this service deals in. */
public final class Domain {

  private Domain() {}

  /**
   * What a customer sent. Untrusted, and full of their own personal details.
   *
   * @param from the address that wrote in
   * @param body what they wrote
   */
  public record Mail(String from, String body) {}

  /** Stored as "mail". Say the name here if you ever want it to outlive renaming the record. */
  public static final OccludedType<Mail> MAIL = OccludedType.of(Mail.class);

  /**
   * A row from the billing system: authoritative, and holding a live card token.
   *
   * @param number the invoice number
   * @param tenant the tenant the invoice belongs to
   * @param customerEmail the address the customer writes from
   * @param amount what is owed
   * @param cardToken the live token for the card on file
   */
  public record Invoice(
      String number, String tenant, String customerEmail, BigDecimal amount, String cardToken) {}

  /** Stored as "invoice". */
  public static final OccludedType<Invoice> INVOICE = OccludedType.of(Invoice.class);

  /**
   * Four digits, which is all an approver needs to recognise a card.
   *
   * @param digits the last four digits of the card
   */
  public record Last4(String digits) {}

  /** Stored as "last4". */
  public static final OccludedType<Last4> LAST4 = OccludedType.of(Last4.class);
}
