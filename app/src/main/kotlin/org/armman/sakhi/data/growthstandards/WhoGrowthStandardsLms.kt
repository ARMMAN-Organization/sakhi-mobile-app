package org.armman.sakhi.data.growthstandards

/**
 * WHO Child Growth Standards (2006) reference data — Lambda-Mu-Sigma (LMS) parameters used by
 * [WhoZScoreCalculator] to turn a raw measurement into a z-score for INFANT_VISIT's three
 * `NUTRITIONAL_ZSCORE` fields (CR-033): `nutritional_status_wasting` (weight-for-length),
 * `nutritional_status_stunting` (length-for-age), `nutritional_status_underweight`
 * (weight-for-age).
 *
 * ## IMPORTANT — data provenance and coverage, read before touching or trusting these numbers
 *
 * This session had no working network path to the authoritative WHO source files
 * (`cdn.who.int`'s LMS tables — blocked by the outbound proxy with a 403 on every attempt; the
 * usual GitHub mirrors of the WHO Anthro `.txt`/`.json` tables 404'd or were blocked by
 * `robots.txt`). Rather than silently ship numbers presented as verified WHO data, here is
 * exactly what these tables are and are not:
 *
 * - **Length-for-age `L = 1.0` for every age/sex below is an exact, well-documented WHO modeling
 *   fact** (WHO fits length/height-for-age as an untransformed normal distribution across the
 *   whole 0-19y standard, so the Box-Cox power is always 1) — this part is not approximated.
 * - **Every other number — every `M` and `S` for length-for-age and weight-for-age, and every
 *   `L`/`M`/`S` for weight-for-length — is a reconstructed approximation**, built from a small
 *   set of commonly-published WHO median anchor points (birth/6/12/24-month medians etc.) plus a
 *   cubic-spline fill for the months/lengths in between, and a coarse linear/near-constant model
 *   for `L`/`S` where the exact WHO curve was not reliably known. These are **not** transcribed
 *   from the official LMS tables and **must be replaced with the verified WHO values** (the
 *   `lenanthro`/`weianthro`/`wflanthro` tables from the WHO Anthro package, or the official
 *   `cdn.who.int` expanded tables) before this classification is relied on for a real referral
 *   decision. Treat every SAM/MAM/SUW/MUW/stunted classification produced today as provisional
 *   until that swap happens.
 * - **Coverage**: length-for-age and weight-for-age cover every whole month 0-24 for both sexes
 *   (the full range CR-033 asked for). Weight-for-length covers every whole centimetre 45-110cm
 *   for both sexes (the WHO table itself is published at 0.5cm increments; this ships at 1cm
 *   resolution — [weightForLengthLms] rounds a query to the nearest embedded whole centimetre
 *   rather than interpolating, the coarser of the two options CR-033 allowed for).
 *
 * TODO(CR-033-verify): swap every table below for the verbatim WHO LMS parameter tables once this
 * environment has network access to the authoritative source. Do not hand-edit individual numbers
 * without re-deriving the whole table the same way — the spline fit is continuous across an
 * anchor set, not independent per point.
 */
object WhoGrowthStandardsLms {

  /** One row of a WHO LMS table: the Box-Cox power ([l]), median ([m]), and coefficient of
   * variation ([s]) consumed by [WhoZScoreCalculator.zScore]. */
  data class Lms(val l: Double, val m: Double, val s: Double)

  /** WHO tables are boy/girl only — see [org.armman.sakhi.data.visitform
   * .InfantVisitFormComputedFieldEvaluator] for how this app's third `sex_of_the_infant` option
   * (`transgender`) is handled (not mapped to either table; returns null rather than guessing). */
  enum class WhoSex { BOY, GIRL }

  /** Length-for-age (stunting) LMS by whole age in months, 0-24. `L` is exactly 1.0 for every
   * entry — see this object's doc. Null outside 0-24. */
  fun lengthForAgeLms(ageMonths: Int, sex: WhoSex): Lms? =
    (if (sex == WhoSex.BOY) LENGTH_FOR_AGE_BOY else LENGTH_FOR_AGE_GIRL)[ageMonths]

  /** Weight-for-age (underweight) LMS by whole age in months, 0-24. Null outside 0-24. */
  fun weightForAgeLms(ageMonths: Int, sex: WhoSex): Lms? =
    (if (sex == WhoSex.BOY) WEIGHT_FOR_AGE_BOY else WEIGHT_FOR_AGE_GIRL)[ageMonths]

  /** Weight-for-length (wasting) LMS by length in cm, looked up at the nearest whole centimetre
   * within the embedded 45-110cm range (see this object's doc on why nearest-cm, not
   * interpolated). Null outside that range rather than extrapolating. */
  fun weightForLengthLms(lengthCm: Double, sex: WhoSex): Lms? {
    if (lengthCm < 45.0 || lengthCm > 110.0) return null
    val cm = Math.round(lengthCm).toInt().coerceIn(45, 110)
    return (if (sex == WhoSex.BOY) WEIGHT_FOR_LENGTH_BOY else WEIGHT_FOR_LENGTH_GIRL)[cm]
  }
  private val LENGTH_FOR_AGE_BOY: Map<Int, Lms> = mapOf(
    0 to Lms(l = 1.0, m = 49.9, s = 0.036),
    1 to Lms(l = 1.0, m = 54.7, s = 0.0364),
    2 to Lms(l = 1.0, m = 58.4, s = 0.0368),
    3 to Lms(l = 1.0, m = 61.4, s = 0.0372),
    4 to Lms(l = 1.0, m = 63.9, s = 0.0376),
    5 to Lms(l = 1.0, m = 65.9, s = 0.038),
    6 to Lms(l = 1.0, m = 67.6, s = 0.0384),
    7 to Lms(l = 1.0, m = 69.2, s = 0.0388),
    8 to Lms(l = 1.0, m = 70.6, s = 0.0392),
    9 to Lms(l = 1.0, m = 72.0, s = 0.0396),
    10 to Lms(l = 1.0, m = 73.3, s = 0.04),
    11 to Lms(l = 1.0, m = 74.5, s = 0.0404),
    12 to Lms(l = 1.0, m = 75.7, s = 0.0408),
    13 to Lms(l = 1.0, m = 76.803, s = 0.0412),
    14 to Lms(l = 1.0, m = 77.843, s = 0.0416),
    15 to Lms(l = 1.0, m = 78.9, s = 0.042),
    16 to Lms(l = 1.0, m = 80.032, s = 0.0424),
    17 to Lms(l = 1.0, m = 81.19, s = 0.0428),
    18 to Lms(l = 1.0, m = 82.3, s = 0.0432),
    19 to Lms(l = 1.0, m = 83.306, s = 0.0436),
    20 to Lms(l = 1.0, m = 84.227, s = 0.044),
    21 to Lms(l = 1.0, m = 85.1, s = 0.0444),
    22 to Lms(l = 1.0, m = 85.962, s = 0.0448),
    23 to Lms(l = 1.0, m = 86.849, s = 0.0452),
    24 to Lms(l = 1.0, m = 87.8, s = 0.0456),
  )

  private val LENGTH_FOR_AGE_GIRL: Map<Int, Lms> = mapOf(
    0 to Lms(l = 1.0, m = 49.1, s = 0.036),
    1 to Lms(l = 1.0, m = 53.7, s = 0.0364),
    2 to Lms(l = 1.0, m = 57.1, s = 0.0368),
    3 to Lms(l = 1.0, m = 59.8, s = 0.0372),
    4 to Lms(l = 1.0, m = 62.1, s = 0.0376),
    5 to Lms(l = 1.0, m = 64.0, s = 0.038),
    6 to Lms(l = 1.0, m = 65.7, s = 0.0384),
    7 to Lms(l = 1.0, m = 67.3, s = 0.0388),
    8 to Lms(l = 1.0, m = 68.7, s = 0.0392),
    9 to Lms(l = 1.0, m = 70.1, s = 0.0396),
    10 to Lms(l = 1.0, m = 71.5, s = 0.04),
    11 to Lms(l = 1.0, m = 72.8, s = 0.0404),
    12 to Lms(l = 1.0, m = 74.0, s = 0.0408),
    13 to Lms(l = 1.0, m = 75.184, s = 0.0412),
    14 to Lms(l = 1.0, m = 76.357, s = 0.0416),
    15 to Lms(l = 1.0, m = 77.5, s = 0.042),
    16 to Lms(l = 1.0, m = 78.6, s = 0.0424),
    17 to Lms(l = 1.0, m = 79.662, s = 0.0428),
    18 to Lms(l = 1.0, m = 80.7, s = 0.0432),
    19 to Lms(l = 1.0, m = 81.722, s = 0.0436),
    20 to Lms(l = 1.0, m = 82.724, s = 0.044),
    21 to Lms(l = 1.0, m = 83.7, s = 0.0444),
    22 to Lms(l = 1.0, m = 84.642, s = 0.0448),
    23 to Lms(l = 1.0, m = 85.545, s = 0.0452),
    24 to Lms(l = 1.0, m = 86.4, s = 0.0456),
  )

  private val WEIGHT_FOR_AGE_BOY: Map<Int, Lms> = mapOf(
    0 to Lms(l = 0.35, m = 3.3, s = 0.146),
    1 to Lms(l = 0.3292, m = 4.5, s = 0.1466),
    2 to Lms(l = 0.3083, m = 5.6, s = 0.1472),
    3 to Lms(l = 0.2875, m = 6.4, s = 0.1478),
    4 to Lms(l = 0.2667, m = 7.0, s = 0.1484),
    5 to Lms(l = 0.2458, m = 7.5, s = 0.149),
    6 to Lms(l = 0.225, m = 7.9, s = 0.1496),
    7 to Lms(l = 0.2042, m = 8.3, s = 0.1502),
    8 to Lms(l = 0.1833, m = 8.6, s = 0.1508),
    9 to Lms(l = 0.1625, m = 8.9, s = 0.1514),
    10 to Lms(l = 0.1417, m = 9.2, s = 0.152),
    11 to Lms(l = 0.1208, m = 9.4, s = 0.1526),
    12 to Lms(l = 0.1, m = 9.6, s = 0.1532),
    13 to Lms(l = 0.0792, m = 9.9, s = 0.1538),
    14 to Lms(l = 0.0583, m = 10.1, s = 0.1544),
    15 to Lms(l = 0.0375, m = 10.3, s = 0.155),
    16 to Lms(l = 0.0167, m = 10.5, s = 0.1556),
    17 to Lms(l = -0.0042, m = 10.7, s = 0.1562),
    18 to Lms(l = -0.025, m = 10.9, s = 0.1568),
    19 to Lms(l = -0.0458, m = 11.1, s = 0.1574),
    20 to Lms(l = -0.0667, m = 11.3, s = 0.158),
    21 to Lms(l = -0.0875, m = 11.5, s = 0.1586),
    22 to Lms(l = -0.1083, m = 11.8, s = 0.1592),
    23 to Lms(l = -0.1292, m = 12.0, s = 0.1598),
    24 to Lms(l = -0.15, m = 12.2, s = 0.1604),
  )

  private val WEIGHT_FOR_AGE_GIRL: Map<Int, Lms> = mapOf(
    0 to Lms(l = -0.2, m = 3.2, s = 0.147),
    1 to Lms(l = -0.2075, m = 4.2, s = 0.1476),
    2 to Lms(l = -0.215, m = 5.1, s = 0.1482),
    3 to Lms(l = -0.2225, m = 5.8, s = 0.1488),
    4 to Lms(l = -0.23, m = 6.4, s = 0.1494),
    5 to Lms(l = -0.2375, m = 6.9, s = 0.15),
    6 to Lms(l = -0.245, m = 7.3, s = 0.1506),
    7 to Lms(l = -0.2525, m = 7.6, s = 0.1512),
    8 to Lms(l = -0.26, m = 7.9, s = 0.1518),
    9 to Lms(l = -0.2675, m = 8.2, s = 0.1524),
    10 to Lms(l = -0.275, m = 8.5, s = 0.153),
    11 to Lms(l = -0.2825, m = 8.7, s = 0.1536),
    12 to Lms(l = -0.29, m = 8.9, s = 0.1542),
    13 to Lms(l = -0.2975, m = 9.2, s = 0.1548),
    14 to Lms(l = -0.305, m = 9.4, s = 0.1554),
    15 to Lms(l = -0.3125, m = 9.6, s = 0.156),
    16 to Lms(l = -0.32, m = 9.8, s = 0.1566),
    17 to Lms(l = -0.3275, m = 10.0, s = 0.1572),
    18 to Lms(l = -0.335, m = 10.2, s = 0.1578),
    19 to Lms(l = -0.3425, m = 10.4, s = 0.1584),
    20 to Lms(l = -0.35, m = 10.6, s = 0.159),
    21 to Lms(l = -0.3575, m = 10.9, s = 0.1596),
    22 to Lms(l = -0.365, m = 11.1, s = 0.1602),
    23 to Lms(l = -0.3725, m = 11.3, s = 0.1608),
    24 to Lms(l = -0.38, m = 11.5, s = 0.1614),
  )

  private val WEIGHT_FOR_LENGTH_BOY: Map<Int, Lms> = mapOf(
    45 to Lms(l = -1.0, m = 2.3, s = 0.088),
    46 to Lms(l = -1.0, m = 2.506, s = 0.088),
    47 to Lms(l = -1.0, m = 2.707, s = 0.0881),
    48 to Lms(l = -1.0, m = 2.904, s = 0.0881),
    49 to Lms(l = -1.0, m = 3.101, s = 0.0882),
    50 to Lms(l = -1.0, m = 3.3, s = 0.0882),
    51 to Lms(l = -1.0, m = 3.503, s = 0.0883),
    52 to Lms(l = -1.0, m = 3.712, s = 0.0883),
    53 to Lms(l = -1.0, m = 3.929, s = 0.0884),
    54 to Lms(l = -1.0, m = 4.158, s = 0.0885),
    55 to Lms(l = -1.0, m = 4.4, s = 0.0885),
    56 to Lms(l = -1.0, m = 4.657, s = 0.0885),
    57 to Lms(l = -1.0, m = 4.928, s = 0.0886),
    58 to Lms(l = -1.0, m = 5.21, s = 0.0886),
    59 to Lms(l = -1.0, m = 5.501, s = 0.0887),
    60 to Lms(l = -1.0, m = 5.8, s = 0.0887),
    61 to Lms(l = -1.0, m = 6.103, s = 0.0888),
    62 to Lms(l = -1.0, m = 6.409, s = 0.0888),
    63 to Lms(l = -1.0, m = 6.712, s = 0.0889),
    64 to Lms(l = -1.0, m = 7.01, s = 0.089),
    65 to Lms(l = -1.0, m = 7.3, s = 0.089),
    66 to Lms(l = -1.0, m = 7.579, s = 0.089),
    67 to Lms(l = -1.0, m = 7.847, s = 0.0891),
    68 to Lms(l = -1.0, m = 8.106, s = 0.0891),
    69 to Lms(l = -1.0, m = 8.356, s = 0.0892),
    70 to Lms(l = -1.0, m = 8.6, s = 0.0892),
    71 to Lms(l = -1.0, m = 8.837, s = 0.0893),
    72 to Lms(l = -1.0, m = 9.068, s = 0.0893),
    73 to Lms(l = -1.0, m = 9.289, s = 0.0894),
    74 to Lms(l = -1.0, m = 9.5, s = 0.0895),
    75 to Lms(l = -1.0, m = 9.7, s = 0.0895),
    76 to Lms(l = -1.0, m = 9.888, s = 0.0895),
    77 to Lms(l = -1.0, m = 10.067, s = 0.0896),
    78 to Lms(l = -1.0, m = 10.242, s = 0.0896),
    79 to Lms(l = -1.0, m = 10.418, s = 0.0897),
    80 to Lms(l = -1.0, m = 10.6, s = 0.0897),
    81 to Lms(l = -1.0, m = 10.791, s = 0.0898),
    82 to Lms(l = -1.0, m = 10.989, s = 0.0898),
    83 to Lms(l = -1.0, m = 11.192, s = 0.0899),
    84 to Lms(l = -1.0, m = 11.397, s = 0.0899),
    85 to Lms(l = -1.0, m = 11.6, s = 0.09),
    86 to Lms(l = -1.0, m = 11.8, s = 0.09),
    87 to Lms(l = -1.0, m = 11.998, s = 0.0901),
    88 to Lms(l = -1.0, m = 12.197, s = 0.0901),
    89 to Lms(l = -1.0, m = 12.396, s = 0.0902),
    90 to Lms(l = -1.0, m = 12.6, s = 0.0902),
    91 to Lms(l = -1.0, m = 12.809, s = 0.0903),
    92 to Lms(l = -1.0, m = 13.023, s = 0.0903),
    93 to Lms(l = -1.0, m = 13.243, s = 0.0904),
    94 to Lms(l = -1.0, m = 13.469, s = 0.0904),
    95 to Lms(l = -1.0, m = 13.7, s = 0.0905),
    96 to Lms(l = -1.0, m = 13.937, s = 0.0905),
    97 to Lms(l = -1.0, m = 14.182, s = 0.0906),
    98 to Lms(l = -1.0, m = 14.439, s = 0.0906),
    99 to Lms(l = -1.0, m = 14.711, s = 0.0907),
    100 to Lms(l = -1.0, m = 15.0, s = 0.0907),
    101 to Lms(l = -1.0, m = 15.309, s = 0.0908),
    102 to Lms(l = -1.0, m = 15.636, s = 0.0909),
    103 to Lms(l = -1.0, m = 15.978, s = 0.0909),
    104 to Lms(l = -1.0, m = 16.334, s = 0.0909),
    105 to Lms(l = -1.0, m = 16.7, s = 0.091),
    106 to Lms(l = -1.0, m = 17.074, s = 0.091),
    107 to Lms(l = -1.0, m = 17.454, s = 0.0911),
    108 to Lms(l = -1.0, m = 17.836, s = 0.0911),
    109 to Lms(l = -1.0, m = 18.219, s = 0.0912),
    110 to Lms(l = -1.0, m = 18.6, s = 0.0912),
  )

  private val WEIGHT_FOR_LENGTH_GIRL: Map<Int, Lms> = mapOf(
    45 to Lms(l = -1.7, m = 2.2, s = 0.09),
    46 to Lms(l = -1.7, m = 2.421, s = 0.09),
    47 to Lms(l = -1.7, m = 2.628, s = 0.0901),
    48 to Lms(l = -1.7, m = 2.824, s = 0.0901),
    49 to Lms(l = -1.7, m = 3.014, s = 0.0902),
    50 to Lms(l = -1.7, m = 3.2, s = 0.0902),
    51 to Lms(l = -1.7, m = 3.386, s = 0.0903),
    52 to Lms(l = -1.7, m = 3.576, s = 0.0903),
    53 to Lms(l = -1.7, m = 3.772, s = 0.0904),
    54 to Lms(l = -1.7, m = 3.979, s = 0.0905),
    55 to Lms(l = -1.7, m = 4.2, s = 0.0905),
    56 to Lms(l = -1.7, m = 4.437, s = 0.0905),
    57 to Lms(l = -1.7, m = 4.688, s = 0.0906),
    58 to Lms(l = -1.7, m = 4.951, s = 0.0906),
    59 to Lms(l = -1.7, m = 5.223, s = 0.0907),
    60 to Lms(l = -1.7, m = 5.5, s = 0.0907),
    61 to Lms(l = -1.7, m = 5.78, s = 0.0908),
    62 to Lms(l = -1.7, m = 6.062, s = 0.0909),
    63 to Lms(l = -1.7, m = 6.344, s = 0.0909),
    64 to Lms(l = -1.7, m = 6.623, s = 0.091),
    65 to Lms(l = -1.7, m = 6.9, s = 0.091),
    66 to Lms(l = -1.7, m = 7.172, s = 0.091),
    67 to Lms(l = -1.7, m = 7.438, s = 0.0911),
    68 to Lms(l = -1.7, m = 7.699, s = 0.0911),
    69 to Lms(l = -1.7, m = 7.953, s = 0.0912),
    70 to Lms(l = -1.7, m = 8.2, s = 0.0912),
    71 to Lms(l = -1.7, m = 8.439, s = 0.0913),
    72 to Lms(l = -1.7, m = 8.67, s = 0.0914),
    73 to Lms(l = -1.7, m = 8.891, s = 0.0914),
    74 to Lms(l = -1.7, m = 9.101, s = 0.0915),
    75 to Lms(l = -1.7, m = 9.3, s = 0.0915),
    76 to Lms(l = -1.7, m = 9.487, s = 0.0915),
    77 to Lms(l = -1.7, m = 9.666, s = 0.0916),
    78 to Lms(l = -1.7, m = 9.841, s = 0.0916),
    79 to Lms(l = -1.7, m = 10.018, s = 0.0917),
    80 to Lms(l = -1.7, m = 10.2, s = 0.0917),
    81 to Lms(l = -1.7, m = 10.391, s = 0.0918),
    82 to Lms(l = -1.7, m = 10.589, s = 0.0919),
    83 to Lms(l = -1.7, m = 10.792, s = 0.0919),
    84 to Lms(l = -1.7, m = 10.996, s = 0.0919),
    85 to Lms(l = -1.7, m = 11.2, s = 0.092),
    86 to Lms(l = -1.7, m = 11.4, s = 0.092),
    87 to Lms(l = -1.7, m = 11.599, s = 0.0921),
    88 to Lms(l = -1.7, m = 11.797, s = 0.0921),
    89 to Lms(l = -1.7, m = 11.997, s = 0.0922),
    90 to Lms(l = -1.7, m = 12.2, s = 0.0922),
    91 to Lms(l = -1.7, m = 12.408, s = 0.0923),
    92 to Lms(l = -1.7, m = 12.622, s = 0.0924),
    93 to Lms(l = -1.7, m = 12.841, s = 0.0924),
    94 to Lms(l = -1.7, m = 13.067, s = 0.0924),
    95 to Lms(l = -1.7, m = 13.3, s = 0.0925),
    96 to Lms(l = -1.7, m = 13.54, s = 0.0925),
    97 to Lms(l = -1.7, m = 13.789, s = 0.0926),
    98 to Lms(l = -1.7, m = 14.047, s = 0.0926),
    99 to Lms(l = -1.7, m = 14.317, s = 0.0927),
    100 to Lms(l = -1.7, m = 14.6, s = 0.0927),
    101 to Lms(l = -1.7, m = 14.897, s = 0.0928),
    102 to Lms(l = -1.7, m = 15.206, s = 0.0929),
    103 to Lms(l = -1.7, m = 15.527, s = 0.0929),
    104 to Lms(l = -1.7, m = 15.859, s = 0.0929),
    105 to Lms(l = -1.7, m = 16.2, s = 0.093),
    106 to Lms(l = -1.7, m = 16.549, s = 0.093),
    107 to Lms(l = -1.7, m = 16.905, s = 0.0931),
    108 to Lms(l = -1.7, m = 17.266, s = 0.0931),
    109 to Lms(l = -1.7, m = 17.631, s = 0.0932),
    110 to Lms(l = -1.7, m = 18.0, s = 0.0932),
  )
}
