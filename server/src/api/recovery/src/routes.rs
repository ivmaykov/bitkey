use std::collections::{HashMap, HashSet};

use ::metrics::KeyValue;
use account::entities::Account;
use axum::Extension;

use axum::routing::{delete, put};
use axum::{
    extract::{Path, State},
    routing::{get, post},
    Json, Router,
};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use time::serde::rfc3339;
use time::{Duration, OffsetDateTime};
use tracing::{event, instrument, Level};
use types::recovery::social::challenge::{
    SocialChallenge, SocialChallengeId, SocialChallengeResponse, TrustedContactChallengeRequest,
};
use types::recovery::social::relationship::{RecoveryRelationship, RecoveryRelationshipId};
use utoipa::{OpenApi, ToSchema};

use account::{
    entities::{CommsVerificationScope, Factor, FullAccountAuthKeysPayload, Touchpoint},
    error::AccountError,
    service::{
        ClearPushTouchpointsInput, CreateAndRotateAuthKeysInput, FetchAccountInput,
        Service as AccountService,
    },
};
use authn_authz::key_claims::KeyClaims;
use authn_authz::userpool::{cognito_user::CognitoUser, UserPoolService};
use bdk_utils::{bdk::bitcoin::secp256k1::PublicKey, signature::check_signature};
use comms_verification::{
    error::CommsVerificationError, InitiateVerificationForScopeInput,
    Service as CommsVerificationService, VerifyForScopeInput,
};
use errors::{ApiError, ErrorCode};
use feature_flags::service::Service as FeatureFlagsService;
use http_server::swagger::{SwaggerEndpoint, Url};
use notification::{entities::NotificationTouchpoint, service::Service as NotificationService};
use types::account::identifiers::{AccountId, TouchpointId};
use wsm_rust_client::WsmClient;

use crate::flags::FLAG_SOCIAL_RECOVERY_ENABLE;
use crate::service::social::challenge::create_social_challenge::CreateSocialChallengeInput;
use crate::service::social::challenge::fetch_social_challenge::{
    FetchSocialChallengeAsCustomerInput, FetchSocialChallengeAsTrustedContactInput,
};
use crate::service::social::challenge::respond_to_social_challenge::RespondToSocialChallengeInput;
use crate::service::social::relationship::accept_recovery_relationship_invitation::AcceptRecoveryRelationshipInvitationInput;
use crate::service::social::relationship::create_recovery_relationship_invitation::CreateRecoveryRelationshipInvitationInput;
use crate::service::social::relationship::delete_recovery_relationship::DeleteRecoveryRelationshipInput;
use crate::service::social::relationship::endorse_recovery_relationships::EndorseRecoveryRelationshipsInput;
use crate::service::social::relationship::get_recovery_relationship_invitation_for_code::GetRecoveryRelationshipInvitationForCodeInput;
use crate::service::social::relationship::get_recovery_relationships::GetRecoveryRelationshipsInput;
use crate::service::social::relationship::reissue_recovery_relationship_invitation::ReissueRecoveryRelationshipInvitationInput;
use crate::{
    entities::{
        DelayNotifyRecoveryAction, DelayNotifyRequirements, RecoveryAction, RecoveryDestination,
        RecoveryRequirements, RecoveryType, ToActor, ToActorStrategy, WalletRecovery,
    },
    error::RecoveryError,
    metrics,
    repository::Repository as RecoveryRepository,
    service::social::challenge::Service as SocialChallengeService,
    service::social::relationship::Service as RecoveryRelationshipService,
    state_machine::{
        cancel_recovery::CanceledRecoveryState, pending_recovery::PendingRecoveryResponse,
        run_recovery_fsm, PendingDelayNotifyRecovery, RecoveryEvent, RecoveryResponse,
    },
};
use types::recovery::social::relationship::RecoveryRelationshipEndorsement;

#[derive(Clone, axum_macros::FromRef)]
pub struct RouteState(
    pub AccountService,
    pub NotificationService,
    pub CommsVerificationService,
    pub UserPoolService,
    pub RecoveryRepository,
    pub WsmClient,
    pub RecoveryRelationshipService,
    pub SocialChallengeService,
    pub FeatureFlagsService,
);

impl RouteState {
    pub fn unauthed_router(&self) -> Router {
        Router::new()
            .route(
                "/api/accounts/:account_id/delay-notify/complete",
                post(complete_delay_notify_transaction),
            )
            .route_layer(metrics::FACTORY.route_layer("recovery".to_owned()))
            .with_state(self.to_owned())
    }

    pub fn authed_router(&self) -> Router {
        Router::new()
            .route(
                "/api/accounts/:account_id/delay-notify",
                post(create_delay_notify),
            )
            .route(
                "/api/accounts/:account_id/delay-notify/test",
                put(update_delay_for_test_account),
            )
            .route(
                "/api/accounts/:account_id/delay-notify",
                delete(cancel_delay_notify),
            )
            .route(
                "/api/accounts/:account_id/recovery",
                get(get_recovery_status),
            )
            .route(
                "/api/accounts/:account_id/delay-notify/send-verification-code",
                post(send_verification_code),
            )
            .route(
                "/api/accounts/:account_id/delay-notify/verify-code",
                post(verify_code),
            )
            .route(
                "/api/accounts/:account_id/authentication-keys",
                post(rotate_authentication_keys),
            )
            .route(
                "/api/accounts/:account_id/recovery/relationships",
                post(create_recovery_relationship),
            )
            .route(
                "/api/accounts/:account_id/recovery/relationships",
                put(endorse_recovery_relationships),
            )
            .route_layer(metrics::FACTORY.route_layer("recovery".to_owned()))
            .with_state(self.to_owned())
    }

    pub fn recovery_authed_router(&self) -> Router {
        Router::new()
            .route(
                "/api/accounts/:account_id/recovery/social-challenges",
                post(start_social_challenge),
            )
            .route(
                "/api/accounts/:account_id/recovery/verify-social-challenge",
                post(verify_social_challenge_code),
            )
            .route(
                "/api/accounts/:account_id/recovery/social-challenges/:social_challenge_id",
                put(respond_to_social_challenge),
            )
            .route(
                "/api/accounts/:account_id/recovery/social-challenges/:social_challenge_id",
                get(fetch_social_challenge),
            )
            .route_layer(metrics::FACTORY.route_layer("recovery".to_owned()))
            .with_state(self.to_owned())
    }

    pub fn account_or_recovery_authed_router(&self) -> Router {
        Router::new()
            .route(
                "/api/accounts/:account_id/recovery/relationships/:recovery_relationship_id",
                delete(delete_recovery_relationship),
            )
            .route(
                "/api/accounts/:account_id/recovery/relationships",
                get(get_recovery_relationships),
            )
            .route(
                "/api/accounts/:account_id/recovery/relationships/:recovery_relationship_id",
                put(update_recovery_relationship),
            )
            .route(
                "/api/accounts/:account_id/recovery/relationship-invitations/:code",
                get(get_recovery_relationship_invitation_for_code),
            )
            .route_layer(metrics::FACTORY.route_layer("recovery".to_owned()))
            .with_state(self.to_owned())
    }
}

impl From<RouteState> for SwaggerEndpoint {
    fn from(_: RouteState) -> Self {
        (
            Url::new("Recovery", "/docs/recovery/openapi.json"),
            ApiDoc::openapi(),
        )
    }
}

#[derive(OpenApi)]
#[openapi(
    paths(
        cancel_delay_notify,
        complete_delay_notify_transaction,
        create_delay_notify,
        create_recovery_relationship,
        delete_recovery_relationship,
        endorse_recovery_relationships,
        fetch_social_challenge,
        get_recovery_relationship_invitation_for_code,
        get_recovery_relationships,
        get_recovery_status,
        respond_to_social_challenge,
        rotate_authentication_keys,
        send_verification_code,
        start_social_challenge,
        update_recovery_relationship,
        verify_code,
        verify_social_challenge_code,
    ),
    components(
        schemas(
            AuthenticationKey,
            CanceledRecoveryState,
            CompleteDelayNotifyRequest,
            CompleteDelayNotifyResponse,
            CreateAccountDelayNotifyRequest,
            CreateRecoveryRelationshipRequest,
            CreateRecoveryRelationshipResponse,
            Customer,
            CustomerSocialChallenge,
            CustomerSocialChallengeResponse,
            DelayNotifyRecoveryAction,
            DelayNotifyRequirements,
            EndorseRecoveryRelationshipsRequest,
            EndorseRecoveryRelationshipsResponse,
            EndorsedTrustedContact,
            Factor,
            FetchSocialChallengeResponse,
            FullAccountAuthKeysPayload,
            GetRecoveryRelationshipInvitationForCodeResponse,
            GetRecoveryRelationshipsResponse,
            InboundInvitation,
            OutboundInvitation,
            PendingDelayNotifyRecovery,
            PendingRecoveryResponse,
            RecoveryAction,
            RecoveryRequirements,
            RecoveryResponse,
            RecoveryRelationshipEndorsement,
            RecoveryType,
            RespondToSocialChallengeRequest,
            RespondToSocialChallengeResponse,
            RotateAuthenticationKeysRequest,
            RotateAuthenticationKeysResponse,
            SendAccountVerificationCodeRequest,
            SendAccountVerificationCodeResponse,
            StartChallengeTrustedContactRequest,
            StartSocialChallengeRequest,
            StartSocialChallengeResponse,
            TrustedContact,
            TrustedContactSocialChallenge,
            UnendorsedTrustedContact,
            UpdateRecoveryRelationshipRequest,
            UpdateRecoveryRelationshipResponse,
            VerifyAccountVerificationCodeRequest,
            VerifyAccountVerificationCodeResponse,
            VerifySocialChallengeCodeRequest,
            VerifySocialChallengeCodeResponse,
            WalletRecovery,
        )
    ),
    tags(
        (name = "Recovery", description = "Recovery for Account and Keysets")
    )
)]
struct ApiDoc;

#[derive(Debug, Serialize, Deserialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub struct CreateAccountDelayNotifyRequest {
    pub lost_factor: Factor, // TODO: [W-774] Update visibility of struct after migration
    pub delay_period_num_sec: Option<i64>, // TODO: [W-774] Update visibility of struct after migration
    pub auth: FullAccountAuthKeysPayload, // TODO: [W-774] Update visibility of struct after migration, [W-973] Remove this before beta
}

#[instrument(
    err,
    skip(
        account_service,
        notification_service,
        recovery_service,
        comms_verification_service
    )
)]
#[utoipa::path(
    post,
    path = "/api/accounts/{account_id}/delay-notify",
    params(
        ("account_id" = AccountId, Path, description = "AccountId"),
    ),
    request_body = CreateAccountDelayNotifyRequest,
    responses(
        (status = 200, description = "D&N Recovery was created", body=PendingRecoveryResponse),
        (status = 404, description = "Account not found")
    ),
)]
pub async fn create_delay_notify(
    Path(account_id): Path<AccountId>,
    State(account_service): State<AccountService>,
    State(notification_service): State<NotificationService>,
    State(recovery_service): State<RecoveryRepository>,
    State(comms_verification_service): State<CommsVerificationService>,
    key_proof: KeyClaims,
    Json(request): Json<CreateAccountDelayNotifyRequest>,
) -> Result<Json<Value>, ApiError> {
    let full_account = account_service
        .fetch_full_account(FetchAccountInput {
            account_id: &account_id,
        })
        .await?;

    let destination = RecoveryDestination {
        source_auth_keys_id: full_account.common_fields.active_auth_keys_id.to_owned(),
        app_auth_pubkey: request.auth.app,
        hardware_auth_pubkey: request.auth.hardware,
        recovery_auth_pubkey: request.auth.recovery,
    };
    let events = vec![
        RecoveryEvent::CheckAccountRecoveryState,
        RecoveryEvent::CreateRecovery {
            account: full_account.clone(),
            lost_factor: request.lost_factor,
            destination,
            key_proof,
        },
    ];
    let create_response = run_recovery_fsm(
        account_id.clone(),
        events,
        &account_service,
        &recovery_service,
        &notification_service,
        &comms_verification_service,
    )
    .await
    .map(|r| Json(r.response()))?;

    //TODO: Remove this once recovery syncer on mobile isn't running always
    if request.delay_period_num_sec.is_some()
        && full_account.common_fields.properties.is_test_account
    {
        update_recovery_delay_for_test_account(
            account_id,
            account_service,
            notification_service,
            recovery_service,
            comms_verification_service,
            UpdateDelayForTestRecoveryRequest {
                delay_period_num_sec: request.delay_period_num_sec,
            },
        )
        .await
    } else {
        Ok(create_response)
    }
}

#[derive(Debug, Serialize, Deserialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub struct UpdateDelayForTestRecoveryRequest {
    pub delay_period_num_sec: Option<i64>, // TODO: [W-774] Update visibility of struct after migration
}

async fn update_recovery_delay_for_test_account(
    account_id: AccountId,
    account_service: AccountService,
    notification_service: NotificationService,
    recovery_service: RecoveryRepository,
    comms_verification_service: CommsVerificationService,
    request: UpdateDelayForTestRecoveryRequest,
) -> Result<Json<Value>, ApiError> {
    let events = vec![
        RecoveryEvent::CheckAccountRecoveryState,
        RecoveryEvent::UpdateDelayForTestAccountRecovery {
            delay_period_num_sec: request.delay_period_num_sec,
        },
    ];
    run_recovery_fsm(
        account_id,
        events,
        &account_service,
        &recovery_service,
        &notification_service,
        &comms_verification_service,
    )
    .await
    .map(|r| Json(r.response()))
}

#[instrument(
    err,
    skip(
        account_service,
        notification_service,
        recovery_service,
        comms_verification_service
    )
)]
#[utoipa::path(
    put,
    path = "/api/accounts/{account_id}/delay-notify/test",
    params(
        ("account_id" = AccountId, Path, description = "AccountId"),
    ),
    request_body = UpdateDelayForTestRecoveryRequest,
    responses(
        (status = 200, description = "D&N Recovery was updated for test accounts only", body=PendingRecoveryResponse),
        (status = 400, description = "Could not update the delay for this account"),
        (status = 404, description = "Account not found")
    ),
)]
pub async fn update_delay_for_test_account(
    Path(account_id): Path<AccountId>,
    State(account_service): State<AccountService>,
    State(notification_service): State<NotificationService>,
    State(recovery_service): State<RecoveryRepository>,
    State(comms_verification_service): State<CommsVerificationService>,
    key_proof: KeyClaims,
    Json(request): Json<UpdateDelayForTestRecoveryRequest>,
) -> Result<Json<Value>, ApiError> {
    update_recovery_delay_for_test_account(
        account_id,
        account_service,
        notification_service,
        recovery_service,
        comms_verification_service,
        request,
    )
    .await
}

#[instrument(
    err,
    skip(
        account_service,
        notification_service,
        recovery_service,
        comms_verification_service
    )
)]
#[utoipa::path(
    delete,
    path = "/api/accounts/{account_id}/delay-notify",
    params(
        ("account_id" = AccountId, Path, description = "AccountId"),
    ),
    responses(
        (status = 200, description = "D&N Recovery was canceled"),
        (status = 404, description = "Account not found")
    ),
)]
pub async fn cancel_delay_notify(
    Path(account_id): Path<AccountId>,
    State(account_service): State<AccountService>,
    State(notification_service): State<NotificationService>,
    State(recovery_service): State<RecoveryRepository>,
    State(comms_verification_service): State<CommsVerificationService>,
    key_proof: KeyClaims,
) -> Result<(), ApiError> {
    let events = vec![
        RecoveryEvent::CheckAccountRecoveryState,
        RecoveryEvent::CancelRecovery { key_proof },
    ];
    run_recovery_fsm(
        account_id,
        events,
        &account_service,
        &recovery_service,
        &notification_service,
        &comms_verification_service,
    )
    .await?;

    Ok(())
}

#[instrument(
    err,
    skip(
        account_service,
        notification_service,
        recovery_service,
        comms_verification_service
    )
)]
#[utoipa::path(
    get,
    path = "/api/accounts/{account_id}/recovery",
    params(
        ("account_id" = AccountId, Path, description = "AccountId"),
    ),
    responses(
        (status = 200, description = "D&N Recovery fetched status", body=RecoveryResponse),
        (status = 404, description = "Account or D&N recovery not found")
    ),
)]
pub async fn get_recovery_status(
    Path(account_id): Path<AccountId>,
    State(account_service): State<AccountService>,
    State(notification_service): State<NotificationService>,
    State(recovery_service): State<RecoveryRepository>,
    State(comms_verification_service): State<CommsVerificationService>,
) -> Result<Json<Value>, ApiError> {
    let events = vec![RecoveryEvent::CheckAccountRecoveryState];
    run_recovery_fsm(
        account_id,
        events,
        &account_service,
        &recovery_service,
        &notification_service,
        &comms_verification_service,
    )
    .await
    .map(|r| Json(r.response()))
}

#[derive(Debug, Serialize, Deserialize, Clone, ToSchema)]
pub struct CompleteDelayNotifyRequest {
    pub challenge: String,
    pub app_signature: String,
    pub hardware_signature: String,
}

#[derive(Serialize, ToSchema)]
pub struct CompleteDelayNotifyResponse {}

#[instrument(
    err,
    skip(
        account_service,
        recovery_service,
        notification_service,
        user_pool_service,
        comms_verification_service,
    )
)]
#[utoipa::path(
    post,
    path = "/api/accounts/{account_id}/delay-notify/complete",
    params(
        ("account_id" = AccountId, Path, description = "AccountId"),
    ),
    request_body = CompleteDelayNotifyRequest,
    responses(
        (status = 200, description = "D&N Recovery transaction was completed", body=CompleteDelayNotifyResponse),
        (status = 400, description = "D&N Recovery not found or recovery still pending."),
        (status = 404, description = "Account not found, D&N recovery not found or D&N recovery still pending.")
    ),
)]
pub async fn complete_delay_notify_transaction(
    Path(account_id): Path<AccountId>,
    State(notification_service): State<NotificationService>,
    State(account_service): State<AccountService>,
    State(recovery_service): State<RecoveryRepository>,
    State(comms_verification_service): State<CommsVerificationService>,
    State(user_pool_service): State<UserPoolService>,
    Json(request): Json<CompleteDelayNotifyRequest>,
) -> Result<Json<Value>, ApiError> {
    let events = vec![
        RecoveryEvent::CheckAccountRecoveryState,
        RecoveryEvent::CheckEligibleForCompletion {
            challenge: request.challenge,
            app_signature: request.app_signature,
            hardware_signature: request.hardware_signature,
        },
        RecoveryEvent::RotateKeyset { user_pool_service },
    ];
    run_recovery_fsm(
        account_id,
        events,
        &account_service,
        &recovery_service,
        &notification_service,
        &comms_verification_service,
    )
    .await
    .map(|r| Json(r.response()))
}

#[derive(Debug, Serialize, Deserialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub struct SendAccountVerificationCodeRequest {
    pub touchpoint_id: TouchpointId, // TODO: [W-774] Update visibility of struct after migration
}

#[derive(Debug, Serialize, Deserialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub struct SendAccountVerificationCodeResponse {}

#[instrument(err, skip(account_service, comms_verification_service))]
#[utoipa::path(
    post,
    path = "/api/accounts/{account_id}/delay-notify/send-verification-code",
    params(
        ("account_id" = AccountId, Path, description = "AccountId"),
    ),
    request_body = SendAccountVerificationCodeRequest,
    responses(
        (status = 200, description = "Verification code sent", body=SendAccountVerificationCodeResponse),
        (status = 404, description = "Touchpoint not found")
    ),
)]
pub async fn send_verification_code(
    Path(account_id): Path<AccountId>,
    State(account_service): State<AccountService>,
    State(comms_verification_service): State<CommsVerificationService>,
    key_proof: KeyClaims,
    Json(request): Json<SendAccountVerificationCodeRequest>,
) -> Result<Json<SendAccountVerificationCodeResponse>, ApiError> {
    let account = account_service
        .fetch_account(account::service::FetchAccountInput {
            account_id: &account_id,
        })
        .await?;

    let actor = key_proof.to_actor(ToActorStrategy::ExclusiveOr)?;
    let scope = CommsVerificationScope::DelayNotifyActor(actor);

    let touchpoint = account
        .get_touchpoint_by_id(request.touchpoint_id.clone())
        .ok_or(AccountError::TouchpointNotFound)?;

    match touchpoint {
        Touchpoint::Email { active, .. } | Touchpoint::Phone { active, .. } => {
            if !*active {
                return Err(RecoveryError::TouchpointStatusMismatch.into());
            }
        }
        _ => {
            return Err(RecoveryError::TouchpointTypeMismatch.into());
        }
    }

    comms_verification_service
        .initiate_verification_for_scope(InitiateVerificationForScopeInput {
            account_id: &account_id,
            account_properties: &account.get_common_fields().properties,
            scope,
            only_touchpoints: Some(HashSet::from([NotificationTouchpoint::from(
                touchpoint.to_owned(),
            )])),
        })
        .await?;

    Ok(Json(SendAccountVerificationCodeResponse {}))
}

#[derive(Debug, Serialize, Deserialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub struct VerifyAccountVerificationCodeRequest {
    pub verification_code: String, // TODO: [W-774] Update visibility of struct after migration
}

#[derive(Debug, Serialize, Deserialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub struct VerifyAccountVerificationCodeResponse {}

#[instrument(err, skip(comms_verification_service))]
#[utoipa::path(
    post,
    path = "/api/accounts/{account_id}/delay-notify/verify-code",
    params(
        ("account_id" = AccountId, Path, description = "AccountId"),
    ),
    request_body = VerifyAccountVerificationCodeRequest,
    responses(
        (status = 200, description = "Verification code sent", body=VerifyAccountVerificationCodeResponse),
        (status = 404, description = "Touchpoint not found")
    ),
)]
pub async fn verify_code(
    Path(account_id): Path<AccountId>,
    State(comms_verification_service): State<CommsVerificationService>,
    key_proof: KeyClaims,
    Json(request): Json<VerifyAccountVerificationCodeRequest>,
) -> Result<Json<VerifyAccountVerificationCodeResponse>, ApiError> {
    let actor = key_proof.to_actor(ToActorStrategy::ExclusiveOr)?;
    let scope = CommsVerificationScope::DelayNotifyActor(actor);

    comms_verification_service
        .verify_for_scope(VerifyForScopeInput {
            account_id,
            scope,
            code: request.verification_code,
            duration: Duration::minutes(10),
        })
        .await
        .map_err(|e| {
            if matches!(e, CommsVerificationError::CodeMismatch) {
                metrics::DELAY_NOTIFY_CODE_SUBMITTED
                    .add(1, &[KeyValue::new(metrics::CODE_MATCHED_KEY, false)]);
            }
            e
        })?;

    metrics::DELAY_NOTIFY_CODE_SUBMITTED.add(1, &[KeyValue::new(metrics::CODE_MATCHED_KEY, true)]);

    Ok(Json(VerifyAccountVerificationCodeResponse {}))
}

#[derive(Debug, Serialize, Deserialize, Clone, ToSchema)]
pub struct AuthenticationKey {
    pub key: PublicKey,
    pub signature: String,
}

#[derive(Debug, Serialize, Deserialize, Clone, ToSchema)]
pub struct RotateAuthenticationKeysRequest {
    pub application: AuthenticationKey,
    pub hardware: AuthenticationKey,
    pub recovery: Option<AuthenticationKey>,
}

#[derive(Serialize, Deserialize, ToSchema)]
pub struct RotateAuthenticationKeysResponse {}

#[instrument(
    err,
    skip(
        notification_service,
        account_service,
        recovery_service,
        comms_verification_service,
        user_pool_service,
    )
)]
#[utoipa::path(
    post,
    path = "/api/accounts/{account_id}/authentication-keys",
    params(
        ("account_id" = AccountId, Path, description = "AccountId"),
    ),
    request_body = RotateAuthenticationKeysRequest,
    responses(
        (status = 200, description = "Rotation of app authentication key was completed.", body=RotateAuthenticationKeysResponse),
        (status = 400, description = "Rotation of app authentication key failed due to invalid signature or keyset."),
        (status = 404, description = "Account not found.")
    ),
)]
pub async fn rotate_authentication_keys(
    Path(account_id): Path<AccountId>,
    State(notification_service): State<NotificationService>,
    State(account_service): State<AccountService>,
    State(recovery_service): State<RecoveryRepository>,
    State(comms_verification_service): State<CommsVerificationService>,
    State(user_pool_service): State<UserPoolService>,
    key_proof: KeyClaims,
    Json(request): Json<RotateAuthenticationKeysRequest>,
) -> Result<Json<RotateAuthenticationKeysResponse>, ApiError> {
    if account_id.to_string() != key_proof.account_id {
        event!(
            Level::ERROR,
            "Account id in path does not match account id in access token"
        );
        return Err(ApiError::GenericBadRequest(
            "Account id in path does not match account id in access token".to_string(),
        ));
    }
    if !(key_proof.hw_signed && key_proof.app_signed) {
        event!(
            Level::WARN,
            "valid signature over access token required by both app and hw auth keys"
        );
        return Err(ApiError::GenericBadRequest(
            "valid signature over access token required by both app and hw auth keys".to_string(),
        ));
    }

    let account = account_service
        .fetch_full_account(FetchAccountInput {
            account_id: &account_id,
        })
        .await?;
    let current_auth = account
        .active_auth_keys()
        .ok_or(AccountError::InvalidKeysetState)?;

    check_signature(
        &account_id.to_string(),
        &request.application.signature,
        request.application.key,
    )?;
    check_signature(
        &account_id.to_string(),
        &request.hardware.signature,
        request.hardware.key,
    )?;

    // Ensure we aren't going from having a recovery authkey to having none
    let existing_recovery_key = current_auth.recovery_pubkey.is_some();
    let rotate_to_new_recovery_key = request.recovery.is_some();
    if existing_recovery_key && !rotate_to_new_recovery_key {
        return Err(ApiError::GenericBadRequest(
            "Recovery Authentication key required".to_string(),
        ));
    }

    // Check signature for recovery authkey if it exists
    if let Some(recovery_auth) = request.recovery.as_ref() {
        check_signature(
            &account_id.to_string(),
            &recovery_auth.signature,
            recovery_auth.key,
        )?;
    }

    //TODO: Remove this when the endpoint should rotate more than just the app key
    if request.hardware.key != current_auth.hardware_pubkey {
        return Err(ApiError::GenericBadRequest(
            "Hardware Authentication key mismatch".to_string(),
        ));
    }

    // Cancel D+N if exists
    let events = vec![
        RecoveryEvent::CheckAccountRecoveryState,
        RecoveryEvent::CancelRecovery { key_proof },
    ];
    if let Err(e) = run_recovery_fsm(
        account_id.clone(),
        events,
        &account_service,
        &recovery_service,
        &notification_service,
        &comms_verification_service,
    )
    .await
    {
        if !matches!(e.clone(), ApiError::Specific{code, ..} if code == ErrorCode::NoRecoveryExists)
        {
            return Err(e);
        }
    }

    account_service
        .create_and_rotate_auth_keys(CreateAndRotateAuthKeysInput {
            account_id: &account_id,
            app_auth_pubkey: request.application.key,
            hardware_auth_pubkey: request.hardware.key,
            recovery_auth_pubkey: request.recovery.as_ref().map(|r| r.key),
        })
        .await?;

    // If there's no existing recovery key and we're adding a new one, we need a new recovery cognito user
    if !existing_recovery_key && rotate_to_new_recovery_key {
        user_pool_service
            .create_recovery_user_if_necessary(&account_id, request.recovery.as_ref().unwrap().key)
            .await
            .map_err(RecoveryError::RotateAuthKeys)?;
    }

    user_pool_service
        .rotate_account_auth_keys(
            &account_id,
            request.application.key,
            request.hardware.key,
            request.recovery.map(|f| f.key),
        )
        .await
        .map_err(RecoveryError::RotateAuthKeys)?;

    account_service
        .clear_push_touchpoints(ClearPushTouchpointsInput {
            account_id: &account_id,
        })
        .await?;

    metrics::AUTH_KEYS_ROTATED.add(1, &[]);

    Ok(Json(RotateAuthenticationKeysResponse {}))
}

// Recovery Relationships

#[derive(Serialize, Deserialize, Debug, ToSchema, PartialEq)]
pub struct OutboundInvitation {
    pub recovery_relationship_id: RecoveryRelationshipId,
    pub trusted_contact_alias: String,
    pub code: String,
    #[serde(with = "rfc3339")]
    pub expires_at: OffsetDateTime,
}

impl TryFrom<RecoveryRelationship> for OutboundInvitation {
    type Error = RecoveryError;

    fn try_from(value: RecoveryRelationship) -> Result<Self, Self::Error> {
        match value {
            RecoveryRelationship::Invitation(invitation) => Ok(Self {
                recovery_relationship_id: invitation.common_fields.id,
                trusted_contact_alias: invitation.common_fields.trusted_contact_alias,
                code: invitation.code,
                expires_at: invitation.expires_at,
            }),
            _ => Err(RecoveryError::InvalidRecoveryRelationshipType),
        }
    }
}

#[derive(Serialize, Deserialize, Debug, ToSchema)]
pub struct InboundInvitation {
    pub recovery_relationship_id: RecoveryRelationshipId,
    #[serde(with = "rfc3339")]
    pub expires_at: OffsetDateTime,
    pub customer_enrollment_pubkey: String,
}

impl TryFrom<RecoveryRelationship> for InboundInvitation {
    type Error = RecoveryError;

    fn try_from(value: RecoveryRelationship) -> Result<Self, Self::Error> {
        match value {
            RecoveryRelationship::Invitation(invitation) => Ok(Self {
                recovery_relationship_id: invitation.common_fields.id,
                expires_at: invitation.expires_at,
                customer_enrollment_pubkey: invitation.customer_enrollment_pubkey,
            }),
            _ => Err(RecoveryError::InvalidRecoveryRelationshipType),
        }
    }
}

//TODO(BKR-919): Remove Clone once we're no longer duplicating this for `unendorsed_trusted_contacts`
#[derive(Serialize, Deserialize, Debug, ToSchema, PartialEq, Clone)]
pub struct TrustedContact {
    pub recovery_relationship_id: RecoveryRelationshipId,
    pub trusted_contact_alias: String,
    pub trusted_contact_identity_pubkey: String,
}

//TODO(BKR-919): Remove Clone once we're no longer duplicating this for `unendorsed_trusted_contacts`
#[derive(Serialize, Deserialize, Debug, ToSchema, PartialEq, Clone)]
pub struct UnendorsedTrustedContact {
    #[serde(flatten)]
    pub info: TrustedContact,
    pub trusted_contact_identity_pubkey_mac: String,
    pub trusted_contact_enrollment_pubkey: String,
    pub enrollment_key_confirmation: String,
}

impl TryFrom<RecoveryRelationship> for UnendorsedTrustedContact {
    type Error = RecoveryError;

    fn try_from(value: RecoveryRelationship) -> Result<Self, Self::Error> {
        match value {
            RecoveryRelationship::Unendorsed(connection) => Ok(Self {
                info: TrustedContact {
                    recovery_relationship_id: connection.common_fields.id,
                    trusted_contact_alias: connection.common_fields.trusted_contact_alias,
                    trusted_contact_identity_pubkey: connection
                        .connection_fields
                        .trusted_contact_identity_pubkey,
                },
                trusted_contact_identity_pubkey_mac: connection.trusted_contact_identity_pubkey_mac,
                trusted_contact_enrollment_pubkey: connection.trusted_contact_enrollment_pubkey,
                enrollment_key_confirmation: connection.enrollment_key_confirmation,
            }),
            _ => Err(RecoveryError::InvalidRecoveryRelationshipType),
        }
    }
}

#[derive(Serialize, Deserialize, Debug, ToSchema, PartialEq)]
pub struct EndorsedTrustedContact {
    #[serde(flatten)]
    pub info: TrustedContact,
    pub endorsement_key_certificate: String,
}

impl TryFrom<RecoveryRelationship> for EndorsedTrustedContact {
    type Error = RecoveryError;

    fn try_from(value: RecoveryRelationship) -> Result<Self, Self::Error> {
        match value {
            RecoveryRelationship::Endorsed(connection) => Ok(Self {
                info: TrustedContact {
                    recovery_relationship_id: connection.common_fields.id,
                    trusted_contact_alias: connection.common_fields.trusted_contact_alias,
                    trusted_contact_identity_pubkey: connection
                        .connection_fields
                        .trusted_contact_identity_pubkey,
                },
                endorsement_key_certificate: connection.endorsement_key_certificate,
            }),
            _ => Err(RecoveryError::InvalidRecoveryRelationshipType),
        }
    }
}

#[derive(Serialize, Deserialize, Debug, ToSchema, PartialEq)]
pub struct Customer {
    pub recovery_relationship_id: RecoveryRelationshipId,
    pub customer_alias: String,
}

impl TryFrom<RecoveryRelationship> for Customer {
    type Error = RecoveryError;

    fn try_from(value: RecoveryRelationship) -> Result<Self, Self::Error> {
        match value {
            RecoveryRelationship::Endorsed(connection) => Ok(Self {
                recovery_relationship_id: connection.common_fields.id,
                customer_alias: connection.connection_fields.customer_alias,
            }),
            RecoveryRelationship::Unendorsed(connection) => Ok(Self {
                recovery_relationship_id: connection.common_fields.id,
                customer_alias: connection.connection_fields.customer_alias,
            }),
            _ => Err(RecoveryError::InvalidRecoveryRelationshipType),
        }
    }
}

#[derive(Debug, Serialize, Deserialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub struct CreateRecoveryRelationshipRequest {
    pub trusted_contact_alias: String,
    //TODO(BKR-919): Remove Option once mobile isn't passing up empty fields
    #[serde(default)]
    pub customer_enrollment_pubkey: String,
}

#[derive(Debug, Serialize, Deserialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub struct CreateRecoveryRelationshipResponse {
    pub invitation: OutboundInvitation,
}

///
/// Used by FullAccounts to create a recovery relationship which is sent to
/// a Trusted Contact (either a FullAccount or a LiteAccount). The trusted contact
/// can then accept the relationship and become a trusted contact for the account.
///
#[instrument(
    err,
    skip(account_service, recovery_relationship_service, feature_flags_service)
)]
#[utoipa::path(
    post,
    path = "/api/accounts/{account_id}/recovery/relationships",
    params(
        ("account_id" = AccountId, Path, description = "AccountId"),
    ),
    request_body = CreateRecoveryRelationshipRequest,
    responses(
        (status = 200, description = "Account creates a recovery relationship", body=CreateRecoveryRelationshipResponse),
    ),
)]
pub async fn create_recovery_relationship(
    Path(account_id): Path<AccountId>,
    State(account_service): State<AccountService>,
    State(recovery_relationship_service): State<RecoveryRelationshipService>,
    State(feature_flags_service): State<FeatureFlagsService>,
    key_proof: KeyClaims,
    Json(request): Json<CreateRecoveryRelationshipRequest>,
) -> Result<Json<CreateRecoveryRelationshipResponse>, ApiError> {
    if !(key_proof.hw_signed && key_proof.app_signed) {
        event!(
            Level::WARN,
            "valid signature over access token required by both app and hw auth keys"
        );
        return Err(ApiError::GenericBadRequest(
            "valid signature over access token required by both app and hw auth keys".to_string(),
        ));
    }

    let full_account = account_service
        .fetch_full_account(FetchAccountInput {
            account_id: &account_id,
        })
        .await?;

    if !FLAG_SOCIAL_RECOVERY_ENABLE
        .resolver(&feature_flags_service)
        .resolve()
    {
        return Err(ApiError::GenericForbidden(
            "Feature not enabled".to_string(),
        ));
    }

    let result = recovery_relationship_service
        .create_recovery_relationship_invitation(CreateRecoveryRelationshipInvitationInput {
            customer_account: &full_account,
            trusted_contact_alias: &request.trusted_contact_alias,
            customer_enrollment_pubkey: &request.customer_enrollment_pubkey,
        })
        .await?;
    Ok(Json(CreateRecoveryRelationshipResponse {
        invitation: result.try_into()?,
    }))
}

///
/// This route is used by either the Customer or the Trusted Contact to delete a pending
/// or an established recovery relationship.
///
/// For Customers, they will need to provide:
/// - Account access token
/// - Both App and Hardware keyproofs
///
/// For Trusted Contacts, they will need to provide:
/// - Recovery access token
///
#[instrument(err, skip(recovery_relationship_service, feature_flags_service))]
#[utoipa::path(
    delete,
    path = "/api/accounts/{account_id}/recovery/relationships/{recovery_relationship_id}",
    params(
        ("account_id" = AccountId, Path, description = "AccountId"),
        ("recovery_relationship_id" = AccountId, Path, description = "RecoveryRelationshipId"),
    ),
    responses(
        (status = 200, description = "Recovery relationship deleted"),
    ),
)]
pub async fn delete_recovery_relationship(
    Path((account_id, recovery_relationship_id)): Path<(AccountId, RecoveryRelationshipId)>,
    State(recovery_relationship_service): State<RecoveryRelationshipService>,
    State(feature_flags_service): State<FeatureFlagsService>,
    key_proof: KeyClaims,
    Extension(cognito_user): Extension<CognitoUser>,
) -> Result<(), ApiError> {
    if !FLAG_SOCIAL_RECOVERY_ENABLE
        .resolver(&feature_flags_service)
        .resolve()
    {
        return Err(ApiError::GenericForbidden(
            "Feature not enabled".to_string(),
        ));
    }

    recovery_relationship_service
        .delete_recovery_relationship(DeleteRecoveryRelationshipInput {
            acting_account_id: &account_id,
            recovery_relationship_id: &recovery_relationship_id,
            key_proof: &key_proof,
            cognito_user: &cognito_user,
        })
        .await?;

    Ok(())
}

#[derive(Debug, Serialize, Deserialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub struct GetRecoveryRelationshipsResponse {
    pub invitations: Vec<OutboundInvitation>,
    //TODO(BKR-919): Remove `trusted_contacts` once the app expects endorsed and unendoresed
    pub trusted_contacts: Vec<UnendorsedTrustedContact>,
    pub unendorsed_trusted_contacts: Vec<UnendorsedTrustedContact>,
    pub endorsed_trusted_contacts: Vec<EndorsedTrustedContact>,
    pub customers: Vec<Customer>,
}

///
/// This route is used by both Customers and Trusted Contacts to retrieve
/// recovery relationships.
///
/// For Customers, we will show:
/// - All the Trusted Contacts that are protecting their account
/// - All the pending outbound invitations
/// - All the accounts that they are protecting as a Trusted Contact
///
/// For Trusted Contacts, we will show:
/// - All the accounts that they are protecting
///
#[instrument(err, skip(recovery_relationship_service, feature_flags_service))]
#[utoipa::path(
    get,
    path = "/api/accounts/{account_id}/recovery/relationships",
    params(
        ("account_id" = AccountId, Path, description = "AccountId"),
    ),
    responses(
        (status = 200, description = "Recovery relationships", body=GetRecoveryRelationshipsResponse),
    ),
)]
pub async fn get_recovery_relationships(
    Path(account_id): Path<AccountId>,
    State(recovery_relationship_service): State<RecoveryRelationshipService>,
    State(feature_flags_service): State<FeatureFlagsService>,
) -> Result<Json<GetRecoveryRelationshipsResponse>, ApiError> {
    if !FLAG_SOCIAL_RECOVERY_ENABLE
        .resolver(&feature_flags_service)
        .resolve()
    {
        return Err(ApiError::GenericForbidden(
            "Feature not enabled".to_string(),
        ));
    }

    let result = recovery_relationship_service
        .get_recovery_relationships(GetRecoveryRelationshipsInput {
            account_id: &account_id,
        })
        .await?;

    let unendorsed_trusted_contacts = result
        .unendorsed_trusted_contacts
        .into_iter()
        .map(|i| i.try_into())
        .collect::<Result<Vec<UnendorsedTrustedContact>, _>>()?;
    Ok(Json(GetRecoveryRelationshipsResponse {
        invitations: result
            .invitations
            .into_iter()
            .map(|i| i.try_into())
            .collect::<Result<Vec<_>, _>>()?,
        trusted_contacts: unendorsed_trusted_contacts.clone(),
        unendorsed_trusted_contacts,
        endorsed_trusted_contacts: result
            .endorsed_trusted_contacts
            .into_iter()
            .map(|i| i.try_into())
            .collect::<Result<Vec<_>, _>>()?,
        customers: result
            .customers
            .into_iter()
            .map(|i| i.try_into())
            .collect::<Result<Vec<_>, _>>()?,
    }))
}

#[derive(Debug, Serialize, Deserialize, ToSchema)]
#[serde(tag = "action")]
pub enum UpdateRecoveryRelationshipRequest {
    Accept {
        code: String,
        customer_alias: String,
        trusted_contact_identity_pubkey: String,
        //TODO(BKR-919): Remove Option once mobile isn't sending up None
        #[serde(default)]
        trusted_contact_enrollment_pubkey: String,
        #[serde(default)]
        trusted_contact_identity_pubkey_mac: String,
        #[serde(default)]
        enrollment_key_confirmation: String,
    },
    Reissue,
}

#[derive(Debug, Serialize, Deserialize, ToSchema, PartialEq)]
#[serde(untagged)]
pub enum UpdateRecoveryRelationshipResponse {
    Accept { customer: Customer },
    Reissue { invitation: OutboundInvitation },
}

///
/// This route is used by either Full Accounts or LiteAccounts to accept
/// an pending outbound invitation and to become a Trusted Contact.
///
#[instrument(
    err,
    skip(account_service, recovery_relationship_service, feature_flags_service)
)]
#[utoipa::path(
    put,
    path = "/api/accounts/{account_id}/recovery/relationships/{recovery_relationship_id}",
    params(
        ("account_id" = AccountId, Path, description = "AccountId"),
        ("recovery_relationship_id" = AccountId, Path, description = "RecoveryRelationshipId"),
    ),
    request_body = UpdateRecoveryRelationshipRequest,
    responses(
        (status = 200, description = "Recovery relationship updated", body=UpdateRecoveryRelationshipResponse),
    ),
)]
pub async fn update_recovery_relationship(
    Path((account_id, recovery_relationship_id)): Path<(AccountId, RecoveryRelationshipId)>,
    State(account_service): State<AccountService>,
    State(recovery_relationship_service): State<RecoveryRelationshipService>,
    State(feature_flags_service): State<FeatureFlagsService>,
    key_proof: KeyClaims,
    Extension(cognito_user): Extension<CognitoUser>,
    Json(request): Json<UpdateRecoveryRelationshipRequest>,
) -> Result<Json<UpdateRecoveryRelationshipResponse>, ApiError> {
    let account = account_service
        .fetch_account(FetchAccountInput {
            account_id: &account_id,
        })
        .await?;

    if !FLAG_SOCIAL_RECOVERY_ENABLE
        .resolver(&feature_flags_service)
        .resolve()
    {
        return Err(ApiError::GenericForbidden(
            "Feature not enabled".to_string(),
        ));
    }

    match request {
        UpdateRecoveryRelationshipRequest::Accept {
            code,
            customer_alias,
            trusted_contact_identity_pubkey,
            trusted_contact_enrollment_pubkey,
            trusted_contact_identity_pubkey_mac,
            enrollment_key_confirmation,
        } => {
            if CognitoUser::Recovery(account_id.clone()) != cognito_user {
                event!(
                    Level::ERROR,
                    "The provided access token is for the incorrect domain."
                );
                return Err(ApiError::GenericForbidden(
                    "The provided access token is for the incorrect domain.".to_string(),
                ));
            }
            let result = recovery_relationship_service
                .accept_recovery_relationship_invitation(
                    AcceptRecoveryRelationshipInvitationInput {
                        trusted_contact_account_id: &account_id,
                        recovery_relationship_id: &recovery_relationship_id,
                        code: &code,
                        customer_alias: &customer_alias,
                        trusted_contact_identity_pubkey: &trusted_contact_identity_pubkey,
                        trusted_contact_enrollment_pubkey: &trusted_contact_enrollment_pubkey,
                        trusted_contact_identity_pubkey_mac: &trusted_contact_identity_pubkey_mac,
                        enrollment_key_confirmation: &enrollment_key_confirmation,
                    },
                )
                .await?;

            Ok(Json(UpdateRecoveryRelationshipResponse::Accept {
                customer: result.try_into()?,
            }))
        }
        UpdateRecoveryRelationshipRequest::Reissue => {
            let Account::Full(full_account) = account else {
                return Err(ApiError::GenericForbidden(
                    "Incorrect calling account type".to_string(),
                ));
            };

            if CognitoUser::Wallet(account_id.clone()) != cognito_user {
                event!(
                    Level::ERROR,
                    "The provided access token is for the incorrect domain."
                );
                return Err(ApiError::GenericForbidden(
                    "The provided access token is for the incorrect domain.".to_string(),
                ));
            }

            if !key_proof.hw_signed || !key_proof.app_signed {
                event!(
                    Level::WARN,
                    "valid signature over access token requires both app and hw auth keys"
                );
                return Err(ApiError::GenericBadRequest(
                    "valid signature over access token requires both app and hw auth keys"
                        .to_string(),
                ));
            }

            let result = recovery_relationship_service
                .reissue_recovery_relationship_invitation(
                    ReissueRecoveryRelationshipInvitationInput {
                        customer_account: &full_account,
                        recovery_relationship_id: &recovery_relationship_id,
                    },
                )
                .await?;

            Ok(Json(UpdateRecoveryRelationshipResponse::Reissue {
                invitation: result.try_into()?,
            }))
        }
    }
}

#[derive(Debug, Serialize, Deserialize, ToSchema)]
pub struct EndorseRecoveryRelationshipsRequest {
    pub endorsements: Vec<RecoveryRelationshipEndorsement>,
}

#[derive(Debug, Serialize, Deserialize, ToSchema, PartialEq)]
pub struct EndorseRecoveryRelationshipsResponse {
    pub unendorsed_trusted_contacts: Vec<UnendorsedTrustedContact>,
    pub endorsed_trusted_contacts: Vec<EndorsedTrustedContact>,
}

///
/// This route is used by Full Accounts to endorse recovery relationships
/// that are accepted by the Trusted Contact
///
#[instrument(
    err,
    skip(account_service, recovery_relationship_service, feature_flags_service)
)]
#[utoipa::path(
    put,
    path = "/api/accounts/{account_id}/recovery/relationships",
    params(
        ("account_id" = AccountId, Path, description = "AccountId"),
    ),
    request_body = EndorseRecoveryRelationshipsRequest,
    responses(
        (status = 200, description = "Recovery relationships endorsed", body=EndorseRecoveryRelationshipsResponse),
    ),
)]
pub async fn endorse_recovery_relationships(
    Path(account_id): Path<AccountId>,
    State(account_service): State<AccountService>,
    State(recovery_relationship_service): State<RecoveryRelationshipService>,
    State(feature_flags_service): State<FeatureFlagsService>,
    key_proof: KeyClaims,
    Json(request): Json<EndorseRecoveryRelationshipsRequest>,
) -> Result<Json<EndorseRecoveryRelationshipsResponse>, ApiError> {
    let account = account_service
        .fetch_account(FetchAccountInput {
            account_id: &account_id,
        })
        .await?;

    if !FLAG_SOCIAL_RECOVERY_ENABLE
        .resolver(&feature_flags_service)
        .resolve()
    {
        return Err(ApiError::GenericForbidden(
            "Feature not enabled".to_string(),
        ));
    }

    let Account::Full(_) = account else {
        return Err(ApiError::GenericForbidden(
            "Incorrect calling account type".to_string(),
        ));
    };

    recovery_relationship_service
        .endorse_recovery_relationships(EndorseRecoveryRelationshipsInput {
            customer_account_id: &account_id,
            endorsements: request.endorsements,
        })
        .await?;
    let result = recovery_relationship_service
        .get_recovery_relationships(GetRecoveryRelationshipsInput {
            account_id: &account_id,
        })
        .await?;

    Ok(Json(EndorseRecoveryRelationshipsResponse {
        unendorsed_trusted_contacts: result
            .unendorsed_trusted_contacts
            .into_iter()
            .map(|i| i.try_into())
            .collect::<Result<Vec<_>, _>>()?,
        endorsed_trusted_contacts: result
            .endorsed_trusted_contacts
            .into_iter()
            .map(|i| i.try_into())
            .collect::<Result<Vec<_>, _>>()?,
    }))
}

#[derive(Debug, Serialize, Deserialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub struct GetRecoveryRelationshipInvitationForCodeResponse {
    pub invitation: InboundInvitation,
}

///
/// This route is used by either FullAccounts or LiteAccounts to retrieve
/// the details of a pending inbound invitation.
///
#[instrument(err, skip(recovery_relationship_service, feature_flags_service))]
#[utoipa::path(
    get,
    path = "/api/accounts/{account_id}/recovery/relationship-invitations/{code}",
    params(
        ("account_id" = AccountId, Path, description = "AccountId"),
        ("code" = String, Path, description = "Code"),
    ),
    responses(
        (status = 200, description = "Recovery relationship invitation", body=GetRecoveryRelationshipInvitationForCodeResponse),
    ),
)]
pub async fn get_recovery_relationship_invitation_for_code(
    Path((account_id, code)): Path<(AccountId, String)>,
    State(recovery_relationship_service): State<RecoveryRelationshipService>,
    State(feature_flags_service): State<FeatureFlagsService>,
) -> Result<Json<GetRecoveryRelationshipInvitationForCodeResponse>, ApiError> {
    if !FLAG_SOCIAL_RECOVERY_ENABLE
        .resolver(&feature_flags_service)
        .resolve()
    {
        return Err(ApiError::GenericForbidden(
            "Feature not enabled".to_string(),
        ));
    }

    let result = recovery_relationship_service
        .get_recovery_relationship_invitation_for_code(
            GetRecoveryRelationshipInvitationForCodeInput { code: &code },
        )
        .await?;

    Ok(Json(GetRecoveryRelationshipInvitationForCodeResponse {
        invitation: result.try_into()?,
    }))
}

// SocialChallenges

#[derive(Debug, Serialize, Deserialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub struct CustomerSocialChallengeResponse {
    pub recovery_relationship_id: RecoveryRelationshipId,
    #[deprecated]
    pub shared_secret_ciphertext: String,
    pub trusted_contact_recovery_pubkey: String,
    pub recovery_key_confirmation: String,
    pub recovery_sealed_pkek: String,
}

impl From<SocialChallengeResponse> for CustomerSocialChallengeResponse {
    fn from(value: SocialChallengeResponse) -> Self {
        Self {
            recovery_relationship_id: value.recovery_relationship_id,
            shared_secret_ciphertext: value.shared_secret_ciphertext,
            trusted_contact_recovery_pubkey: value.trusted_contact_recovery_pubkey,
            recovery_key_confirmation: value.recovery_key_confirmation,
            recovery_sealed_pkek: value.recovery_sealed_pkek,
        }
    }
}

#[derive(Debug, Serialize, Deserialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub struct CustomerSocialChallenge {
    pub social_challenge_id: SocialChallengeId,
    pub code: String,
    pub responses: Vec<CustomerSocialChallengeResponse>,
}

impl From<SocialChallenge> for CustomerSocialChallenge {
    fn from(value: SocialChallenge) -> Self {
        Self {
            social_challenge_id: value.id,
            code: value.code,
            responses: value
                .responses
                .into_iter()
                .map(|r| r.into())
                .collect::<Vec<_>>(),
        }
    }
}

#[derive(Debug, Serialize, Deserialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub struct TrustedContactSocialChallenge {
    pub social_challenge_id: SocialChallengeId,
    pub customer_identity_pubkey: String,
    #[deprecated]
    pub customer_ephemeral_pubkey: String,
    pub customer_recovery_pubkey: String,
    pub enrollment_sealed_pkek: String,
}

impl TryFrom<(RecoveryRelationshipId, SocialChallenge)> for TrustedContactSocialChallenge {
    type Error = RecoveryError;

    fn try_from(
        (recovery_relationship_id, challenge): (RecoveryRelationshipId, SocialChallenge),
    ) -> Result<Self, Self::Error> {
        //TODO(BKR-919): Switch this to return an error if the challenge request does not exist
        let info = challenge
            .trusted_contact_challenge_requests
            .get(&recovery_relationship_id)
            .map(|r| r.to_owned())
            .unwrap_or(TrustedContactChallengeRequest {
                customer_recovery_pubkey: "".to_string(),
                enrollment_sealed_pkek: "".to_string(),
            });

        Ok(Self {
            social_challenge_id: challenge.id,
            customer_identity_pubkey: challenge.customer_identity_pubkey,
            customer_ephemeral_pubkey: challenge.customer_ephemeral_pubkey,
            customer_recovery_pubkey: info.customer_recovery_pubkey,
            enrollment_sealed_pkek: info.enrollment_sealed_pkek,
        })
    }
}

#[derive(Serialize, Deserialize, Debug, Clone, ToSchema)]
pub struct StartChallengeTrustedContactRequest {
    pub recovery_relationship_id: RecoveryRelationshipId,
    #[serde(flatten)]
    pub challenge_request: TrustedContactChallengeRequest,
}

#[derive(Debug, Serialize, Deserialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub struct StartSocialChallengeRequest {
    pub customer_identity_pubkey: String,
    #[serde(default)]
    #[deprecated]
    pub customer_ephemeral_pubkey: String,
    #[serde(default)]
    pub trusted_contacts: Vec<StartChallengeTrustedContactRequest>,
}

#[derive(Debug, Serialize, Deserialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub struct StartSocialChallengeResponse {
    pub social_challenge: CustomerSocialChallenge,
}

///
/// Used by the Customer to initiate a Social challenge.
///
/// The customer must provide a valid recovery authentication token to start
/// the challenge
///
#[instrument(
    err,
    skip(
        account_service,
        social_challenge_service,
        recovery_relationship_service,
        feature_flags_service
    )
)]
#[utoipa::path(
    post,
    path = "/api/accounts/{account_id}/recovery/social-challenges",
    params(
        ("account_id" = AccountId, Path, description = "AccountId"),
    ),
    request_body = StartSocialChallengeRequest,
    responses(
        (status = 200, description = "Social challenge started", body=StartSocialChallengeResponse),
    ),
)]
pub async fn start_social_challenge(
    Path(account_id): Path<AccountId>,
    State(account_service): State<AccountService>,
    State(social_challenge_service): State<SocialChallengeService>,
    State(recovery_relationship_service): State<RecoveryRelationshipService>,
    State(feature_flags_service): State<FeatureFlagsService>,
    Json(request): Json<StartSocialChallengeRequest>,
) -> Result<Json<StartSocialChallengeResponse>, ApiError> {
    let customer_account = account_service
        .fetch_full_account(FetchAccountInput {
            account_id: &account_id,
        })
        .await?;

    if !FLAG_SOCIAL_RECOVERY_ENABLE
        .resolver(&feature_flags_service)
        .resolve()
    {
        return Err(ApiError::GenericForbidden(
            "Feature not enabled".to_string(),
        ));
    }

    let requests = request
        .trusted_contacts
        .into_iter()
        .map(|t| {
            let recovery_relationship_id = t.recovery_relationship_id;
            let challenge = t.challenge_request.clone();
            (recovery_relationship_id, challenge)
        })
        .collect::<HashMap<_, _>>();
    let result = social_challenge_service
        .create_social_challenge(CreateSocialChallengeInput {
            customer_account_id: &customer_account.id,
            customer_identity_pubkey: &request.customer_identity_pubkey,
            customer_ephemeral_pubkey: &request.customer_ephemeral_pubkey,
            requests,
        })
        .await?;

    Ok(Json(StartSocialChallengeResponse {
        social_challenge: result.into(),
    }))
}

#[derive(Debug, Serialize, Deserialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub struct VerifySocialChallengeCodeRequest {
    pub recovery_relationship_id: RecoveryRelationshipId,
    pub code: String,
}

#[derive(Debug, Serialize, Deserialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub struct VerifySocialChallengeCodeResponse {
    pub social_challenge: TrustedContactSocialChallenge,
}

///
/// This route is used by Trusted Contacts to retrieve the social challenge
/// given the code and the recovery relationship. The code was given to them
/// by the Customer who's account they're protecting.
///
#[instrument(err, skip(social_challenge_service, feature_flags_service))]
#[utoipa::path(
    post,
    path = "/api/accounts/{account_id}/recovery/verify-social-challenge",
    params(
        ("account_id" = AccountId, Path, description = "AccountId"),
    ),
    request_body = VerifySocialChallengeCodeRequest,
    responses(
        (status = 200, description = "Social challenge code verified", body=VerifySocialChallengeCodeResponse),
    ),
)]
pub async fn verify_social_challenge_code(
    Path(account_id): Path<AccountId>,
    State(social_challenge_service): State<SocialChallengeService>,
    State(feature_flags_service): State<FeatureFlagsService>,
    Json(request): Json<VerifySocialChallengeCodeRequest>,
) -> Result<Json<VerifySocialChallengeCodeResponse>, ApiError> {
    if !FLAG_SOCIAL_RECOVERY_ENABLE
        .resolver(&feature_flags_service)
        .resolve()
    {
        return Err(ApiError::GenericForbidden(
            "Feature not enabled".to_string(),
        ));
    }

    let challenge = social_challenge_service
        .fetch_social_challenge_as_trusted_contact(FetchSocialChallengeAsTrustedContactInput {
            trusted_contact_account_id: &account_id,
            recovery_relationship_id: &request.recovery_relationship_id,
            code: &request.code,
        })
        .await?;
    let social_challenge = (request.recovery_relationship_id, challenge).try_into()?;
    Ok(Json(VerifySocialChallengeCodeResponse { social_challenge }))
}

#[derive(Debug, Serialize, Deserialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub struct RespondToSocialChallengeRequest {
    #[serde(default)]
    #[deprecated]
    pub shared_secret_ciphertext: String,
    #[serde(default)]
    pub trusted_contact_recovery_pubkey: String,
    #[serde(default)]
    pub recovery_key_confirmation: String,
    #[serde(default)]
    pub recovery_sealed_pkek: String,
}

#[derive(Debug, Serialize, Deserialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub struct RespondToSocialChallengeResponse {}

///
/// This route is used by Trusted Contacts to attest the social challenge
/// and to provide the shared secret that the Customer will use to recover
/// their account.
///
#[instrument(err, skip(social_challenge_service, feature_flags_service))]
#[utoipa::path(
    put,
    path = "/api/accounts/{account_id}/recovery/social-challenges/{social_challenge_id}",
    params(
        ("account_id" = AccountId, Path, description = "AccountId"),
        ("social_challenge_id" = AccountId, Path, description = "SocialChallengeId"),
    ),
    request_body = RespondToSocialChallengeRequest,
    responses(
        (status = 200, description = "Responded to social challenge", body=RespondToSocialChallengeResponse),
    ),
)]
pub async fn respond_to_social_challenge(
    Path((account_id, social_challenge_id)): Path<(AccountId, SocialChallengeId)>,
    State(social_challenge_service): State<SocialChallengeService>,
    State(feature_flags_service): State<FeatureFlagsService>,
    Json(request): Json<RespondToSocialChallengeRequest>,
) -> Result<Json<RespondToSocialChallengeResponse>, ApiError> {
    if !FLAG_SOCIAL_RECOVERY_ENABLE
        .resolver(&feature_flags_service)
        .resolve()
    {
        return Err(ApiError::GenericForbidden(
            "Feature not enabled".to_string(),
        ));
    }

    social_challenge_service
        .respond_to_social_challenge(RespondToSocialChallengeInput {
            trusted_contact_account_id: &account_id,
            social_challenge_id: &social_challenge_id,
            shared_secret_ciphertext: &request.shared_secret_ciphertext,
            trusted_contact_recovery_pubkey: &request.trusted_contact_recovery_pubkey,
            recovery_key_confirmation: &request.recovery_key_confirmation,
            recovery_sealed_pkek: &request.recovery_sealed_pkek,
        })
        .await?;

    Ok(Json(RespondToSocialChallengeResponse {}))
}

#[derive(Debug, Serialize, Deserialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub struct FetchSocialChallengeResponse {
    pub social_challenge: CustomerSocialChallenge,
}

///
/// Used by the Customer to fetch a Pending Social challenge.
///
/// The customer must provide a valid recovery authentication token
/// to check the status of the challenge.
///
#[instrument(
    err,
    skip(account_service, social_challenge_service, feature_flags_service)
)]
#[utoipa::path(
    get,
    path = "/api/accounts/{account_id}/recovery/social-challenges/{social_challenge_id}",
    params(
        ("account_id" = AccountId, Path, description = "AccountId"),
        ("social_challenge_id" = AccountId, Path, description = "SocialChallengeId"),
    ),
    responses(
        (status = 200, description = "Social challenge", body=FetchSocialChallengeResponse),
    ),
)]
pub async fn fetch_social_challenge(
    Path((account_id, social_challenge_id)): Path<(AccountId, SocialChallengeId)>,
    State(account_service): State<AccountService>,
    State(social_challenge_service): State<SocialChallengeService>,
    State(feature_flags_service): State<FeatureFlagsService>,
) -> Result<Json<FetchSocialChallengeResponse>, ApiError> {
    let customer_account = account_service
        .fetch_full_account(FetchAccountInput {
            account_id: &account_id,
        })
        .await?;

    if !FLAG_SOCIAL_RECOVERY_ENABLE
        .resolver(&feature_flags_service)
        .resolve()
    {
        return Err(ApiError::GenericForbidden(
            "Feature not enabled".to_string(),
        ));
    }

    let result = social_challenge_service
        .fetch_social_challenge_as_customer(FetchSocialChallengeAsCustomerInput {
            customer_account_id: &customer_account.id,
            social_challenge_id: &social_challenge_id,
        })
        .await?;

    Ok(Json(FetchSocialChallengeResponse {
        social_challenge: result.into(),
    }))
}
