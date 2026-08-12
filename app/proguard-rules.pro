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
