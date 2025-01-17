use std::str::FromStr;

use authn_authz::userpool::{CreateRecoveryUserInput, CreateWalletUserInput};
use bdk_utils::flags::{
    DEFAULT_MAINNET_ELECTRUM_RPC_URI, DEFAULT_SIGNET_ELECTRUM_RPC_URI,
    DEFAULT_TESTNET_ELECTRUM_RPC_URI,
};
use http::StatusCode;
use isocountry::CountryCode;
use notification::service::{
    FetchNotificationsPreferencesInput, UpdateNotificationsPreferencesInput,
};
use rand::{thread_rng, Rng};
use time::format_description::well_known::Rfc3339;
use time::{Duration, OffsetDateTime};
use types::notification::{NotificationCategory, NotificationChannel};
use types::recovery::social::relationship::{RecoveryRelationship, RecoveryRelationshipId};
use ulid::Ulid;

use account::entities::{
    Factor, FullAccount, FullAccountAuthKeysPayload, Keyset, Network, SpendingKeysetRequest,
    Touchpoint, TouchpointPlatform,
};
use account::entities::{FullAccountAuthKeys, LiteAccount, LiteAccountAuthKeys, SpendingKeyset};
use account::service::{
    ActivateTouchpointForAccountInput, AddPushTouchpointToAccountInput,
    CreateAccountAndKeysetsInput, CreateLiteAccountInput, FetchAccountInput,
    FetchOrCreateEmailTouchpointInput, FetchOrCreatePhoneTouchpointInput,
};
use bdk_utils::bdk::bitcoin::bip32::{
    DerivationPath, ExtendedPrivKey, ExtendedPubKey, Fingerprint,
};
use bdk_utils::bdk::bitcoin::hashes::sha256;
use bdk_utils::bdk::bitcoin::secp256k1::{Message, PublicKey, Secp256k1, SecretKey};
use bdk_utils::bdk::bitcoin::Network as BitcoinNetwork;
use bdk_utils::bdk::keys::{DescriptorSecretKey, KeyMap};
use bdk_utils::bdk::miniscript::descriptor::{DescriptorXKey, Wildcard};
use bdk_utils::bdk::wallet::{get_funded_wallet, AddressIndex, AddressInfo};
use bdk_utils::bdk::{
    bitcoin::psbt::PartiallySignedTransaction,
    database::{AnyDatabase, MemoryDatabase},
    miniscript::DescriptorPublicKey,
    FeeRate,
};
use bdk_utils::bdk::{SignOptions, SyncOptions, Wallet as BdkWallet};
use bdk_utils::{get_blockchain, DescriptorKeyset, ElectrumRpcUris};
use external_identifier::ExternalIdentifier;
use onboarding::routes::{CreateAccountRequest, CreateKeysetRequest};
use recovery::entities::{
    DelayNotifyRecoveryAction, DelayNotifyRequirements, RecoveryAction, RecoveryDestination,
    RecoveryRequirements, RecoveryStatus, RecoveryType, WalletRecovery,
};
use types::account::identifiers::{AccountId, AuthKeysId, KeysetId, TouchpointId};

use crate::Services;

use super::requests::axum::TestClient;

const RECEIVE_DERIVATION_PATH: &str = "m/0";
const CHANGE_DERIVATION_PATH: &str = "m/1";

const TEST_APP_AUTH_KEY: &[u8] = &[0xcd; 32];
const TEST_HW_AUTH_KEY: &[u8] = &[0xab; 32];
const TEST_RECOVERY_AUTH_KEY: &[u8] = &[0xef; 32];

pub(crate) fn gen_external_wallet_address() -> AddressInfo {
    let external_wallet = get_funded_wallet("wpkh([c258d2e4/84h/1h/0h]tpubDDYkZojQFQjht8Tm4jsS3iuEmKjTiEGjG6KnuFNKKJb5A6ZUCUZKdvLdSDWofKi4ToRCwb9poe1XdqfUnP4jaJjCB2Zwv11ZLgSbnZSNecE/1/*)").0;
    external_wallet.get_address(AddressIndex::New).unwrap()
}

const DEFAULT_DERIVATION_PATH_STR: &str = "m/84'/1'/0'";

pub(crate) fn default_electrum_rpc_uris() -> ElectrumRpcUris {
    ElectrumRpcUris {
        mainnet: DEFAULT_MAINNET_ELECTRUM_RPC_URI.to_owned(),
        testnet: DEFAULT_TESTNET_ELECTRUM_RPC_URI.to_owned(),
        signet: DEFAULT_SIGNET_ELECTRUM_RPC_URI.to_owned(),
    }
}

pub(crate) fn create_descriptor_keys(
    network: Network,
) -> (DescriptorSecretKey, DescriptorPublicKey) {
    let derivation_path = DerivationPath::from_str(DEFAULT_DERIVATION_PATH_STR).unwrap();
    let (parent_fingerprint, xprv, xpub) = create_keys(network);
    let xpubkey = DescriptorXKey {
        origin: Some((parent_fingerprint, derivation_path.clone())),
        xkey: xpub,
        derivation_path: DerivationPath::master(),
        wildcard: Wildcard::Unhardened,
    };
    let xprvkey = DescriptorXKey {
        origin: Some((parent_fingerprint, derivation_path)),
        xkey: xprv,
        derivation_path: DerivationPath::master(),
        wildcard: Wildcard::Unhardened,
    };
    (
        DescriptorSecretKey::XPrv(xprvkey),
        DescriptorPublicKey::XPub(xpubkey),
    )
}

pub(crate) fn create_keys(network: Network) -> (Fingerprint, ExtendedPrivKey, ExtendedPubKey) {
    let secp = Secp256k1::new();
    let mut rng = thread_rng();
    let seed: [u8; 32] = rng.gen();
    let derivation_path = DerivationPath::from_str(DEFAULT_DERIVATION_PATH_STR).unwrap();
    let sk = ExtendedPrivKey::new_master(network.to_owned().into(), &seed).unwrap();
    let derived_sk = sk.derive_priv(&secp, &derivation_path).unwrap();
    (
        sk.fingerprint(&secp),
        derived_sk,
        ExtendedPubKey::from_priv(&secp, &derived_sk),
    )
}

pub(crate) fn create_keypair() -> (SecretKey, PublicKey) {
    let secp = Secp256k1::new();
    secp.generate_keypair(&mut thread_rng())
}
pub(crate) fn create_pubkey() -> PublicKey {
    let (_, pk) = create_keypair();
    pk
}

pub(crate) fn create_plain_keys() -> (SecretKey, PublicKey) {
    let secp = Secp256k1::new();
    secp.generate_keypair(&mut thread_rng())
}

/// Get static public keys for app auth and hw auth. The private keys that coorespond to these are used to sign
/// proofs-of-possession in the request builder's `.authenticated` method. Use these keys when you need to auto-generate
/// proof-of-possession in your requests
pub(crate) fn get_static_test_authkeys() -> (PublicKey, PublicKey, PublicKey) {
    let secp = Secp256k1::new();
    let app = SecretKey::from_slice(TEST_APP_AUTH_KEY)
        .expect("parsing byte buffer as secret key should always work")
        .public_key(&secp);
    let hw = SecretKey::from_slice(TEST_HW_AUTH_KEY)
        .expect("parsing byte buffer as secret key should always work")
        .public_key(&secp);
    let recovery = SecretKey::from_slice(TEST_RECOVERY_AUTH_KEY)
        .expect("parsing byte buffer as secret key should always work")
        .public_key(&secp);
    (app, hw, recovery)
}

/// Return randomly generated pubkeys, one for app and one for hw
/// Use this when you do not need to sign auth challenges or do proof-of-possession
/// but just need pubkeys for a test
pub(crate) fn get_random_auth_pubkeys() -> (PublicKey, PublicKey) {
    let app = create_pubkey();
    let hw = create_pubkey();
    (app, hw)
}

pub(crate) fn create_spend_keyset(network: Network) -> (SpendingKeyset, BdkWallet<AnyDatabase>) {
    let (app_xprv, app_xpub) = create_descriptor_keys(network);
    let (_, hardware_xpub) = create_descriptor_keys(network);
    let (_, server_xpub) = create_descriptor_keys(network);
    let keyset = SpendingKeyset::new(
        network.to_owned(),
        app_xpub.clone(),
        hardware_xpub.clone(),
        server_xpub.clone(),
    );
    let wallet = create_bdk_wallet(
        &app_xprv.to_string(),
        &app_xpub.to_string(),
        &hardware_xpub.to_string(),
        &server_xpub.to_string(),
        network.into(),
    );
    (keyset, wallet)
}

pub(crate) async fn create_default_account_with_predefined_wallet(
    client: &TestClient,
    services: &Services,
) -> (FullAccount, BdkWallet<AnyDatabase>) {
    let (account, wallet) =
        create_default_account_with_predefined_wallet_internal(client, services).await;
    (account, wallet)
}

async fn create_default_account_with_predefined_wallet_internal(
    client: &TestClient,
    services: &Services,
) -> (FullAccount, BdkWallet<AnyDatabase>) {
    let network = Network::BitcoinSignet;
    let app_dprv = DescriptorSecretKey::from_str("[7699ff15/84'/1'/0']tprv8iy8auG1S2uBHrd5SWEW3gQELVDMoqur43KKPkbVGDXGTmwLZ4P2Lq7iXA6SzWEh5qB7AG26ENcYqLeDVCgRaJDMXJBdn8A8T5VnZJ6A6C9/*").unwrap();
    let app_dpub = DescriptorPublicKey::from_str("[7699ff15/84'/1'/0']tpubDFfAjKJFaQarBKesL9u6T64LuWjHyB6kdLv6gGdngVKfJGC7BTCcXKjahFxgMfPSgCPyoVFmK4ALuq4L3pk7p2i2FEgBJdGVmbpxty9MQzw/*").unwrap();
    let hardware_dpub = DescriptorPublicKey::from_str("[08254319/84'/1'/0']tpubDEitjEyJG3W5vcinDWPD1sYtY4EmePDrPXCSs15Q7TkR78Fuyi21X1UpEpYXdoWc9sUJbsWpkd77VN8ZiJMHcnHvUhmsRqapsUdU7Mzrncf/*").unwrap();
    let auth = create_auth_keyset_model(network);

    let response = client
        .create_account(&CreateAccountRequest::Full {
            auth: FullAccountAuthKeysPayload {
                app: auth.app_pubkey,
                hardware: auth.hardware_pubkey,
                recovery: auth.recovery_pubkey,
            },
            spending: SpendingKeysetRequest {
                network: Network::BitcoinSignet.into(),
                app: app_dpub.clone(),
                hardware: hardware_dpub.clone(),
            },
            is_test_account: true,
        })
        .await;
    assert_eq!(
        response.status_code,
        StatusCode::OK,
        "{}",
        response.body_string
    );
    let response = response.body.unwrap();
    let keyset = response.keyset.expect("Account should have a keyset");
    let server_dpub = keyset.spending;

    let account = services
        .account_service
        .fetch_full_account(FetchAccountInput {
            account_id: &response.account_id,
        })
        .await
        .unwrap();

    let wallet = create_bdk_wallet(
        &app_dprv.to_string(),
        &app_dpub.to_string(),
        &hardware_dpub.to_string(),
        &server_dpub.to_string(),
        network.into(),
    );
    (account, wallet)
}

pub(crate) fn create_auth_keyset_model(_network: Network) -> FullAccountAuthKeys {
    let (app, hw, recovery) = get_static_test_authkeys();
    FullAccountAuthKeys::new(app, hw, Some(recovery))
}

pub(crate) fn create_lite_auth_keyset_model() -> LiteAccountAuthKeys {
    let (_, _, recovery) = get_static_test_authkeys();
    LiteAccountAuthKeys::new(recovery)
}

pub(crate) async fn create_inactive_spending_keyset_for_account(
    client: &TestClient,
    account_id: &AccountId,
    network: Network,
) -> KeysetId {
    let (_, spend_app) = create_descriptor_keys(network);
    let (_, spend_hw) = create_descriptor_keys(network);

    let response = client
        .create_keyset(
            &account_id.to_string(),
            &CreateKeysetRequest {
                spending: SpendingKeysetRequest {
                    network: network.into(),
                    app: spend_app,
                    hardware: spend_hw,
                },
            },
        )
        .await;
    assert_eq!(
        response.status_code,
        StatusCode::OK,
        "{}",
        response.body_string
    );
    let create_keyset_response = response.body.unwrap();
    create_keyset_response.keyset_id
}

pub(crate) async fn create_account(
    services: &Services,
    network: Network,
    override_auth_keys: Option<FullAccountAuthKeys>,
) -> FullAccount {
    let auth = if let Some(auth) = override_auth_keys {
        auth
    } else {
        create_auth_keyset_model(network)
    };

    let (spend, _) = create_spend_keyset(network);
    let account = services
        .account_service
        .create_account_and_keysets(CreateAccountAndKeysetsInput {
            account_id: AccountId::gen().unwrap(),
            network: spend.network,
            keyset_id: KeysetId::new(Ulid::default()).unwrap(),
            auth_key_id: AuthKeysId::new(Ulid::default()).unwrap(),
            keyset: Keyset {
                auth: FullAccountAuthKeys {
                    app_pubkey: auth.app_pubkey,
                    hardware_pubkey: auth.hardware_pubkey,
                    recovery_pubkey: auth.recovery_pubkey,
                },
                spending: SpendingKeyset {
                    network,
                    app_dpub: spend.app_dpub,
                    hardware_dpub: spend.hardware_dpub,
                    server_dpub: spend.server_dpub,
                },
            },
            is_test_account: network != Network::BitcoinMain,
        })
        .await
        .unwrap();
    services
        .userpool_service
        .create_users(
            &account.id,
            Some(CreateWalletUserInput::new(
                auth.app_pubkey,
                auth.hardware_pubkey,
            )),
            auth.recovery_pubkey.map(CreateRecoveryUserInput::new),
        )
        .await
        .unwrap();
    account
}

pub(crate) async fn create_lite_account(
    services: &Services,
    override_auth_keys: Option<LiteAccountAuthKeys>,
    is_test_account: bool,
) -> LiteAccount {
    let auth = if let Some(auth) = override_auth_keys {
        auth
    } else {
        create_lite_auth_keyset_model()
    };

    let account = services
        .account_service
        .create_lite_account(CreateLiteAccountInput {
            account_id: &AccountId::gen().unwrap(),
            auth_key_id: AuthKeysId::new(Ulid::default()).unwrap(),
            auth: auth.clone(),
            is_test_account,
        })
        .await
        .unwrap();
    let recovery_key = Some(CreateRecoveryUserInput::new(auth.recovery_pubkey));
    services
        .userpool_service
        .create_users(&account.id, None, recovery_key)
        .await
        .unwrap();
    account
}

pub(crate) async fn create_phone_touchpoint(
    services: &Services,
    account_id: &AccountId,
    activate: bool,
) -> TouchpointId {
    let touchpoint = services
        .account_service
        .fetch_or_create_phone_touchpoint(FetchOrCreatePhoneTouchpointInput {
            account_id: account_id.to_owned(),
            phone_number: format!("+1{}", if activate { "5555555555" } else { "5555555556" }),
            country_code: CountryCode::as_array().first().unwrap().to_owned(),
        })
        .await
        .unwrap();

    if let Touchpoint::Phone { id, .. } = touchpoint {
        if activate {
            services
                .account_service
                .activate_touchpoint_for_account(ActivateTouchpointForAccountInput {
                    account_id: account_id.to_owned(),
                    touchpoint_id: id.clone(),
                })
                .await
                .unwrap();

            let current_notification_preferences = services
                .notification_service
                .fetch_notifications_preferences(FetchNotificationsPreferencesInput { account_id })
                .await
                .unwrap();
            services
                .notification_service
                .update_notifications_preferences(UpdateNotificationsPreferencesInput {
                    account_id,
                    notifications_preferences: &current_notification_preferences.with_enabled(
                        NotificationCategory::AccountSecurity,
                        NotificationChannel::Sms,
                    ),
                })
                .await
                .unwrap();
        }

        Some(id)
    } else {
        None
    }
    .unwrap()
}

pub(crate) async fn create_push_touchpoint(
    services: &Services,
    account_id: &AccountId,
) -> Touchpoint {
    let touchpoint = services
        .account_service
        .add_push_touchpoint_for_account(AddPushTouchpointToAccountInput {
            account_id: account_id.to_owned(),
            use_local_sns: true,
            platform: TouchpointPlatform::ApnsTeam,
            device_token: "test-device-token".to_owned(),
        })
        .await
        .unwrap();

    let current_notification_preferences = services
        .notification_service
        .fetch_notifications_preferences(FetchNotificationsPreferencesInput { account_id })
        .await
        .unwrap();
    services
        .notification_service
        .update_notifications_preferences(UpdateNotificationsPreferencesInput {
            account_id,
            notifications_preferences: &current_notification_preferences.with_enabled(
                NotificationCategory::AccountSecurity,
                NotificationChannel::Push,
            ),
        })
        .await
        .unwrap();

    touchpoint
}

pub(crate) async fn create_email_touchpoint(
    services: &Services,
    account_id: &AccountId,
    activate: bool,
) -> TouchpointId {
    let touchpoint = services
        .account_service
        .fetch_or_create_email_touchpoint(FetchOrCreateEmailTouchpointInput {
            account_id: account_id.to_owned(),
            email_address: "bitcoin@example.com".to_string(),
        })
        .await
        .unwrap();

    if let Touchpoint::Email { id, .. } = touchpoint {
        if activate {
            services
                .account_service
                .activate_touchpoint_for_account(ActivateTouchpointForAccountInput {
                    account_id: account_id.to_owned(),
                    touchpoint_id: id.clone(),
                })
                .await
                .unwrap();

            let current_notification_preferences = services
                .notification_service
                .fetch_notifications_preferences(FetchNotificationsPreferencesInput { account_id })
                .await
                .unwrap();
            services
                .notification_service
                .update_notifications_preferences(UpdateNotificationsPreferencesInput {
                    account_id,
                    notifications_preferences: &current_notification_preferences.with_enabled(
                        NotificationCategory::AccountSecurity,
                        NotificationChannel::Email,
                    ),
                })
                .await
                .unwrap();
        }

        Some(id)
    } else {
        None
    }
    .unwrap()
}

fn create_bdk_wallet(
    app_xprv: &str,
    app_xpub: &str,
    hw_xpub: &str,
    server_xpub: &str,
    network: BitcoinNetwork,
) -> BdkWallet<AnyDatabase> {
    let app = DescriptorPublicKey::from_str(app_xpub).unwrap();
    let hw = DescriptorPublicKey::from_str(hw_xpub).unwrap();
    let server = DescriptorPublicKey::from_str(server_xpub).unwrap();

    let keyset = DescriptorKeyset::new(network, app, hw, server);
    let receive_keymap = gen_keymap_with_derivation_path(
        &DescriptorSecretKey::from_str(app_xprv).unwrap(),
        &DescriptorPublicKey::from_str(app_xpub).unwrap(),
        DerivationPath::from_str(RECEIVE_DERIVATION_PATH).unwrap(),
    );
    let change_keymap = gen_keymap_with_derivation_path(
        &DescriptorSecretKey::from_str(app_xprv).unwrap(),
        &DescriptorPublicKey::from_str(app_xpub).unwrap(),
        DerivationPath::from_str(CHANGE_DERIVATION_PATH).unwrap(),
    );
    let receive_descriptor = keyset.receiving().into_multisig_descriptor().unwrap();
    let change_descriptor = keyset.change().into_multisig_descriptor().unwrap();
    let wallet = BdkWallet::new(
        (receive_descriptor, receive_keymap),
        Some((change_descriptor, change_keymap)),
        network,
        AnyDatabase::Memory(MemoryDatabase::new()),
    )
    .unwrap();
    let rpc_uris = default_electrum_rpc_uris();
    let blockchain = get_blockchain(network, &rpc_uris).unwrap();
    wallet.sync(&blockchain, SyncOptions::default()).unwrap();
    wallet
}

pub(crate) fn build_transaction_with_amount(
    wallet: &BdkWallet<AnyDatabase>,
    recipient: AddressInfo,
    amt: u64,
) -> PartiallySignedTransaction {
    let mut builder = wallet.build_tx();
    builder
        .add_recipient(recipient.script_pubkey(), amt)
        .fee_rate(FeeRate::from_sat_per_vb(5.0));
    let (mut tx, _) = builder.finish().unwrap();
    let _ = wallet.sign(
        &mut tx,
        SignOptions {
            remove_partial_sigs: false,
            ..SignOptions::default()
        },
    );
    tx
}

pub(crate) fn build_sweep_transaction(
    wallet: &BdkWallet<AnyDatabase>,
    recipient: AddressInfo,
) -> PartiallySignedTransaction {
    let mut builder = wallet.build_tx();
    builder
        .drain_wallet()
        .drain_to(recipient.script_pubkey())
        .fee_rate(FeeRate::from_sat_per_vb(5.0));
    let (mut tx, _) = builder.finish().unwrap();
    let _ = wallet.sign(
        &mut tx,
        SignOptions {
            remove_partial_sigs: false,
            ..SignOptions::default()
        },
    );
    tx
}

fn gen_keymap_with_derivation_path(
    xprv: &DescriptorSecretKey,
    xpub: &DescriptorPublicKey,
    derivation_path: DerivationPath,
) -> KeyMap {
    let derived_xprv = match xprv {
        DescriptorSecretKey::XPrv(xkey) => DescriptorSecretKey::XPrv(DescriptorXKey {
            derivation_path: derivation_path.clone(),
            origin: xkey.origin.clone(),
            ..*xkey
        }),
        _ => panic!("Invalid Secret Key"),
    };
    let derived_xpub = match xpub {
        DescriptorPublicKey::XPub(xkey) => DescriptorPublicKey::XPub(DescriptorXKey {
            derivation_path,
            origin: xkey.origin.clone(),
            ..*xkey
        }),
        _ => panic!("Invalid Public Key"),
    };
    KeyMap::from([(derived_xpub, derived_xprv)])
}

pub fn gen_signature(message: &str, secret_key: &SecretKey) -> String {
    let secp = Secp256k1::new();
    let message = Message::from_hashed_data::<sha256::Hash>(message.as_bytes());
    secp.sign_ecdsa(&message, secret_key).to_string()
}

pub(crate) fn generate_delay_and_notify_recovery(
    account_id: AccountId,
    destination: RecoveryDestination,
    delay_end_time: OffsetDateTime,
    recovery_status: RecoveryStatus,
    lost_factor: Factor,
) -> WalletRecovery {
    let initiation_time = OffsetDateTime::now_utc() - Duration::days(1);
    WalletRecovery {
        account_id,
        created_at: initiation_time,
        recovery_status,
        recovery_type: RecoveryType::DelayAndNotify,
        recovery_type_time: format!(
            "{}:{}",
            RecoveryType::DelayAndNotify,
            initiation_time.format(&Rfc3339).unwrap()
        ),
        requirements: RecoveryRequirements {
            delay_notify_requirements: DelayNotifyRequirements {
                lost_factor,
                delay_end_time,
            }
            .into(),
        },
        recovery_action: RecoveryAction {
            delay_notify_action: DelayNotifyRecoveryAction {
                destination: destination.clone(),
            }
            .into(),
        },
        destination_app_auth_pubkey: Some(destination.app_auth_pubkey),
        destination_hardware_auth_pubkey: Some(destination.hardware_auth_pubkey),
        destination_recovery_auth_pubkey: destination.recovery_auth_pubkey,
        updated_at: initiation_time,
    }
}

pub(crate) async fn update_recovery_relationship_invitation_expiration(
    services: &Services,
    recovery_relationship_id: &RecoveryRelationshipId,
    expires_at: OffsetDateTime,
) {
    let relationship = services
        .recovery_relationship_service
        .repository
        .fetch_recovery_relationship(recovery_relationship_id)
        .await
        .unwrap();

    let mut invitation = match relationship {
        RecoveryRelationship::Invitation(invitation) => invitation,
        _ => panic!("Expected relationship to be an invitation"),
    };

    invitation.expires_at = expires_at;

    services
        .recovery_relationship_service
        .repository
        .persist_recovery_relationship(&RecoveryRelationship::Invitation(invitation))
        .await
        .unwrap();
}
