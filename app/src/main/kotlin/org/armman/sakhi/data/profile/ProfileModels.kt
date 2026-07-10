package org.armman.sakhi.data.profile

/**
 * Sakhi profile as the future profile API is expected to return it.
 * Bank account arrives pre-masked from the server — never store it in full.
 */
data class SakhiProfile(
  val name: String,
  val sakhiId: String,
  val projectName: String,
  val cardNumber: String,
  val mobileNumber: String,
  val maskedBankAccount: String,
)
