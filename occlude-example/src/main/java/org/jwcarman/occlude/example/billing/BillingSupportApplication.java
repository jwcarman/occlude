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

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** A billing support desk that happens to handle cardholder data and untrusted mail. */
@SpringBootApplication
public class BillingSupportApplication {

  /** Creates the application class, which Spring Boot instantiates as its configuration. */
  public BillingSupportApplication() {}

  /**
   * Starts the support desk.
   *
   * @param args the command line arguments, passed to Spring Boot
   */
  public static void main(String[] args) {
    SpringApplication.run(BillingSupportApplication.class, args);
  }
}
