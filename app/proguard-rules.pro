-keep class net.sqlcipher.** { *; }
-keepattributes Signature, *Annotation*

# Gson (de)serializes model classes via reflection using their Kotlin property names/generic
# signatures. Without these — Gson's own recommended R8 rules — minification renames/strips
# fields nothing calls directly, silently turning network responses into empty objects and
# crashing ("Abstract classes can't be instantiated") on TypeToken-based (de)serialization, e.g.
# every field in data/auth's DTOs and persisted-session models (AuthApi.kt, SessionStore.kt,
# OfflineCredentialCache.kt).
-keepattributes Signature
-keepattributes *Annotation*
-keep class sun.misc.Unsafe { *; }
-keep class com.google.gson.stream.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# Wire/storage-contract model classes Gson constructs purely via reflection (never `new`d
# directly in app code, only referenced through Retrofit's erased Response<T> generic or
# gson.fromJson(json, X::class.java)) are invisible to R8's reachability analysis. Without a
# whole-class -keep, R8 drops the class from the dex entirely — GsonConverterFactory then hands
# Response.body() a type that isn't there, and reading it throws ClassCastException
# (RemoteAuthRepository.loginOnline / SessionStore / OfflineCredentialCache).
-keep class org.armman.sakhi.data.auth.** { *; }

# Same reflection hazard as data/auth above, for the other packages whose DTOs are only
# reached via Retrofit's erased Response<T> generic or gson.fromJson(json, X::class.java) —
# never `new`d directly, so R8 drops them from the dex in release builds unless kept whole.
# forms: FormsApi/FormSubmissionApi + RemoteFormsRepository/DynamicFormSyncExecutor
# (this is the package that was stripped and caused "Couldn't load the enrollment form").
# enrollment: EnrollmentApi (createBeneficiary) + EnrollmentSyncExecutor/RoomEnrollmentRepository.
# lookup: LookupApi (getCategory) + RemoteLookupRepository.
# childregistration: ChildFormDraftPayload via ChildFormSyncExecutor's gson.fromJson.
# motherlink: BeneficiaryApi (GET /beneficiaries, /beneficiaries/:id) + its list/detail DTOs
# (BeneficiaryListResponseDto, BeneficiaryListItemDto, BeneficiaryPiiDto, BeneficiaryDetailDto,
# SocioDemographicsDto, etc.) reached only via Retrofit's erased Response<T> generic — this is the
# package that was stripped and caused the release-only "Select mother" / "Connect to the internet
# once to load your registered mothers" failure (RemoteMotherLinkRepository.fetchMothers() catches
# the resulting Gson/cast exception and returns null, which reads exactly like an offline device).
-keep class org.armman.sakhi.data.forms.** { *; }
-keep class org.armman.sakhi.data.enrollment.** { *; }
-keep class org.armman.sakhi.data.lookup.** { *; }
-keep class org.armman.sakhi.data.childregistration.** { *; }
-keep class org.armman.sakhi.data.motherlink.** { *; }

# schedule: VisitScheduleApi (bulk upload) + BulkVisitScheduleRequestDto/ResponseDto/
# VisitScheduleUploadResultDto + VisitScheduleEntity — reached only via Retrofit's erased
# Response<T> generic or Room's reflection, never `new`d directly from app code that R8's
# reachability analysis can see.
# visitform: VisitApi/FormSubmissionApi request-response DTOs for the two-call visit submit
# (CreateVisitInstanceRequestDto/ResponseDto, CreateSubmissionRequestDto) + VisitFormDraftEntity/
# VisitFormDraftPayload (CR-026b, gson.fromJson'd from SecureKeyValueStore, same reflection
# hazard as childregistration's ChildFormDraftPayload above).
# Missing these two packages is what caused the release-only regression of the ORIGINAL
# "beneficiary's data hasn't finished syncing yet" bug — the same NotYetSynced condition, but
# now caused by R8 silently stripping fields off the schedule-sync/visit-submit DTOs (debug
# builds have isMinifyEnabled = false, so this only ever shows up in a release build).
-keep class org.armman.sakhi.data.schedule.** { *; }
-keep class org.armman.sakhi.data.visitform.** { *; }

# dashboard: DashboardApi (GET /sakhi/{sakhiId}/dashboard) + DashboardResponseDto/DashboardDataDto/
# BeneficiarySummaryDto/ReferralSummaryDto/VisitSummaryDto/DashboardSakhiDto, reached only via
# Retrofit's erased Response<T> generic, plus RemoteDashboardRepository's private
# CachedDashboardSummary (gson.fromJson'd from SecureKeyValueStore, same on-disk-cache reflection
# hazard as the other packages above) — none of these are `new`d directly from app code that R8's
# reachability analysis can see. Missing this is the release-only "dashboard not working" bug
# (2026-08-19): fetchSummary() catches the resulting Gson/cast exception and returns null exactly
# like being offline, and with nothing cached yet getSummary() throws, so HomeViewModel renders
# HomeUiState.Error — debug builds have isMinifyEnabled = false, so this only shows up in release.
-keep class org.armman.sakhi.data.dashboard.** { *; }

# Full audit (2026-08-19) of every remaining Retrofit-backed / Gson-reflected package in the app,
# triggered by the dashboard R8 bug above turning out to be one of several, not a one-off. Same
# exact hazard each time: DTOs reached only via Retrofit's erased Response<T> generic, or
# SecureKeyValueStore draft payloads only ever read back via gson.fromJson(json, X::class.java) —
# never `new`d/referenced by field name anywhere R8's reachability analysis can see, so release
# builds (isMinifyEnabled = true) silently strip or rename their fields while debug builds
# (isMinifyEnabled = false) work fine. Rather than wait for each of these to surface as its own
# release-only bug report, keeping every remaining data/* package that touches Gson or Retrofit:
# closure: ClosureApi (POST /closures) + ClosureRequestDto/ClosureResponseDto/ClosureResponseDataDto.
# referral: ReferralApi (GET pending-followup) + ReferralFollowUp*Dto, plus
# RemoteReferralRepository's own on-disk Gson cache (same cached-shape pattern as dashboard's
# CachedDashboardSummary above).
# reopen: ReopenApi (POST/GET reopen-requests) + ReopenRequest*Dto.
# rules: RuleSetApi (GET published rule version) + PublishedRuleVersionDto/RuleSetEnvelopeDto — feeds
# the on-device GoRules evaluator, so a silent parse failure here would misfire ANC/PP/NN/INC/CCV/HR
# schedule generation release-only, the same category of bug as dashboard but harder to notice.
# visit / visittracker: VisitApi (GET pada visits)/PadaApi (GET padas) + PadaVisit*Dto/PadaListDataDto,
# plus RemoteVisitRepository's/RemotePadaRepository's own on-disk Gson caches (CachedVisit,
# CachedPadaVisits, etc.) — the Pada/Visit-tracker screens' equivalent of the dashboard bug.
# adhocform / delivery: no Retrofit DTOs of their own (they submit through forms'/FormSubmissionApi),
# but AdHocFormDraftPayload/DeliveryFormDraftPayload/DeliveryChildRegistrationDraftPayload are
# SecureKeyValueStore-persisted Gson payloads read back via gson.fromJson — same field-stripping
# hazard as childregistration's ChildFormDraftPayload / visitform's VisitFormDraftPayload above.
-keep class org.armman.sakhi.data.closure.** { *; }
-keep class org.armman.sakhi.data.referral.** { *; }
-keep class org.armman.sakhi.data.reopen.** { *; }
-keep class org.armman.sakhi.data.rules.** { *; }
-keep class org.armman.sakhi.data.visit.** { *; }
-keep class org.armman.sakhi.data.visittracker.** { *; }
-keep class org.armman.sakhi.data.adhocform.** { *; }
-keep class org.armman.sakhi.data.delivery.** { *; }

# previsithealth: PreVisitHealthHistoryApi (GET /beneficiaries/:id/visit-history) +
# VisitHistoryResponseDto/VisitHistoryDataDto/VisitHistoryEntryDto/VisitVitalsDto/VitalValueDto/
# BloodPressureDto, reached only via Retrofit's erased Response<T> generic — same reflection
# hazard as every package above, missed by the 2026-08-19 audit. Release-only regression: the
# Pre-Visit Health History screen's "We couldn't load this beneficiary's health history" error
# on Start Visit (works fine in debug, fails in release) — confirmed 2026-08-25.
-keep class org.armman.sakhi.data.previsithealth.** { *; }

# Retrofit's own recommended R8 rules. Retrofit builds each call's generic return type
# (Response<LoginResponseDto>) from the service interface method's signature/annotations at
# runtime; stripping those causes GsonConverterFactory to hand back the wrong type and
# Response.body() throws ClassCastException the moment it's read (RemoteAuthRepository.loginOnline).
-keepattributes Exceptions
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
  @retrofit2.http.* <methods>;
}
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Google Tink (used transitively) references errorprone annotations that are
# compile-time only and absent at runtime; safe to suppress per R8's own guidance.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi

# GoRules zen-engine (io.gorules.zen_engine.kotlin_android.ZenEngine et al, see
# data/rules/ZenRuleEvaluator.kt) — a Rust-core decision engine bound into Kotlin via JNI/native
# bindings. R8 renaming these classes/methods breaks native linkage: the native library resolves
# Java methods by their original (unobfuscated) class/method names, so a rename produces
# UnsatisfiedLinkError/NoSuchMethodError at the call site instead of a compile error. This is what
# caused the 2026-08-27 release-only "no visits generated for enrolled women" regression —
# GoRulesScheduleFeatureFlag.ENABLED was reverted to false as the immediate fix; these rules are
# required before it can be safely turned back on. Whole-package keep, matching the pattern used
# for every other reflection/native-sensitive package above.
-keep class io.gorules.** { *; }
-keepclassmembers class io.gorules.** { *; }
-dontwarn io.gorules.**

# JNA (com.sun.jna) — the native-binding library GoRules' zen-engine-kotlin-android artifact uses
# under the hood to call into its Rust core. Missed by the io.gorules keep rule above because JNA
# is a separate transitive dependency, not part of the io.gorules.** package tree. JNA resolves
# native struct field offsets via reflection on ITS OWN class/field names at runtime (see
# com.sun.jna.Native/com.sun.jna.Pointer) — R8 renaming/stripping those in release builds breaks
# that resolution immediately on the first native call. Confirmed via a real release-build device
# log (2026-08-31): "ZenRuleEvaluator.evaluate: threw NoClassDefFoundError — com.sun.jna.Native"
# and "... threw UnsatisfiedLinkError — Can't obtain peer field ID for class com.sun.jna.Pointer",
# both caught by GoRulesRiskAdapter's/ZenRuleEvaluator's Throwable-catch (so no crash — every
# on-device risk-grading evaluate() call just silently returns null, and HR field highlighting
# never appears). Debug builds unaffected (isMinifyEnabled = false there) — same failure shape as
# the 2026-08-27 io.gorules regression above, one dependency layer deeper.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
