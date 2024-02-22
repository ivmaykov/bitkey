use crate::{
    entities::{AccountProperties, LiteAccount},
    error::AccountError,
};

use super::{CreateLiteAccountInput, Service};

impl Service {
    pub async fn create_lite_account(
        &self,
        input: CreateLiteAccountInput<'_>,
    ) -> Result<LiteAccount, AccountError> {
        let account_id = input.clone().account_id.to_owned();
        let is_test_account = input.is_test_account;
        let lite_account = LiteAccount::new(
            account_id,
            input.clone().auth_key_id,
            input.clone().auth,
            AccountProperties {
                is_test_account,
                ..AccountProperties::default()
            },
        );
        self.repo.persist(&lite_account.clone().into()).await?;
        Ok(lite_account)
    }
}