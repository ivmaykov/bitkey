use crate::{
    entities::{CommsVerificationClaim, FullAccount},
    error::AccountError,
};

use super::{FetchAccountInput, FetchOrCreateCommsVerificationClaimInput, Service};

impl Service {
    pub async fn fetch_or_create_comms_verification_claim(
        &self,
        input: FetchOrCreateCommsVerificationClaimInput,
    ) -> Result<CommsVerificationClaim, AccountError> {
        let full_account = self
            .fetch_full_account(FetchAccountInput {
                account_id: &input.account_id,
            })
            .await?;

        if let Some(existing_claim) = full_account
            .comms_verification_claims
            .iter()
            .find(|c| c.scope == input.scope)
        {
            return Ok(existing_claim.to_owned());
        }

        let new_claim = CommsVerificationClaim::new(input.scope, None);

        // Add new claim
        let mut comms_verification_claims = full_account.comms_verification_claims;
        comms_verification_claims.push(new_claim.to_owned());

        let updated_account = FullAccount {
            comms_verification_claims,
            ..full_account
        }
        .into();
        self.repo.persist(&updated_account).await?;

        Ok(new_claim)
    }
}
