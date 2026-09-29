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

import org.jwcarman.occlude.RefusedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HTTP surface.
 *
 * <p>References cross the wire as ids, which is all a {@link String} is. A client can hold one, log
 * it, put it in a URL and hand it back tomorrow; none of that is permission to read anything.
 */
@RestController
@RequestMapping("/disputes")
public class DisputeController {

  private final DisputeService disputes;

  /**
   * Creates the controller.
   *
   * @param disputes the support desk this surface fronts
   */
  public DisputeController(DisputeService disputes) {
    this.disputes = disputes;
  }

  /**
   * A customer's message, as it arrives.
   *
   * @param from the address that wrote in
   * @param body what they wrote
   */
  public record Raise(String from, String body) {}

  /**
   * An id and nothing more; holding one is not permission to read anything.
   *
   * @param id the reference to a stored value
   */
  public record Reference(String id) {}

  /**
   * Receives a customer's message, stored at the caller's own tenant.
   *
   * @param raise the message
   * @return the reference to the stored message
   */
  // No tenant parameter. The source reads it from the access, which is established from the
  // request by CurrentAccess, so there is nowhere for this method to get it wrong.
  @PostMapping
  public Reference raise(@RequestBody Raise raise) {
    return new Reference(disputes.receive(new Domain.Mail(raise.from(), raise.body())));
  }

  /**
   * Whether the message mentions this text, without the message leaving the store.
   *
   * @param id the reference to the message
   * @param text the text to look for
   * @return true when the message mentions it
   */
  @GetMapping("/{id}/mentions")
  public boolean mentions(@PathVariable String id, @RequestParam String text) {
    return disputes.mentions(id, text);
  }

  /**
   * Confirms the invoice a message claims.
   *
   * @param id the reference to the message
   * @return the reference to the endorsed invoice
   */
  @PostMapping("/{id}/confirm")
  public Reference confirm(@PathVariable String id) {
    return new Reference(disputes.confirm(id));
  }

  /**
   * The last four digits of the card, for an approver.
   *
   * @param id the reference to the invoice
   * @return the last four digits
   */
  @GetMapping("/{id}/card")
  public Domain.Last4 card(@PathVariable String id) {
    return disputes.cardForApproval(id);
  }

  /**
   * The invoice as the support screen may show it.
   *
   * @param id the reference to the invoice
   * @return the invoice
   */
  @GetMapping("/{id}")
  public Domain.Invoice invoice(@PathVariable String id) {
    return disputes.forSupportScreen(id);
  }

  /**
   * Issues the refund, which is the only place the card token is revealed.
   *
   * @param id the reference to the invoice
   * @return what was refunded
   */
  @PostMapping("/{id}/refund")
  public String refund(@PathVariable String id) {
    return disputes.refund(id);
  }

  /**
   * A refusal is a 403, and says which gate said no without saying what was behind it.
   *
   * @param e the refusal
   * @return the 403 response, carrying the refusal message
   */
  @ExceptionHandler(RefusedException.class)
  public ResponseEntity<String> refused(RefusedException e) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
  }
}
