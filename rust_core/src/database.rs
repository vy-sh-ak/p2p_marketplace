use std::sync::OnceLock;
use std::time::Duration;

use sqlx::postgres::{PgConnectOptions, PgPool, PgPoolOptions, PgSslMode};
use sqlx::FromRow;

use crate::config;

const DB_PORT: u16 = 5432;
const DB_NAME: &str = "postgres";
const DB_USER: &str = "postgres";
const MAX_CONNECTIONS: u32 = 5;
const ACQUIRE_TIMEOUT: Duration = Duration::from_secs(10);

static DB_POOL: OnceLock<PgPool> = OnceLock::new();

#[derive(Debug, Clone, FromRow, uniffi::Record)]
pub struct ChatRoom {
    pub id: i64,
    pub room_id: String,
    pub created_at: String,
    pub offer: Option<String>,
    pub answer: Option<String>,
}

const ROOM_COLUMNS: &str = "id, room_id, created_at::text, offer, answer";

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum DbError {
    #[error("init() must be called before using the database")]
    NotInitialized,
    #[error("supabase_url '{url}' is not a valid project url (expected https://<ref>.supabase.co)")]
    InvalidConfig { url: String },
    #[error("database is not connected, call connect_database() first")]
    NotConnected,
    #[error("failed to connect to database: {detail}")]
    Connection { detail: String },
    #[error("query failed: {detail}")]
    Query { detail: String },
    #[error("chat row with id {id} not found")]
    NotFound { id: i64 },
}

/// Builds the connection pool for the Supabase Postgres database.
///
/// The db host is derived from the project url passed to init()
/// (https://<ref>.supabase.co -> db.<ref>.supabase.co) and the db password
/// is passed to PgConnectOptions directly, so no url-encoding is needed.
/// Idempotent: calling it again when already connected is a no-op.
#[uniffi::export(async_runtime = "tokio")]
pub async fn connect_database() -> Result<(), DbError> {
    if DB_POOL.get().is_some() {
        return Ok(());
    }

    let cfg = config().ok_or(DbError::NotInitialized)?;
    let project_ref = project_ref(&cfg.supabase_url).ok_or_else(|| DbError::InvalidConfig {
        url: cfg.supabase_url.clone(),
    })?;

    let options = PgConnectOptions::new()
        .host(&format!("db.{project_ref}.supabase.co"))
        .port(DB_PORT)
        .database(DB_NAME)
        .username(DB_USER)
        .password(&cfg.supabase_db_password)
        .ssl_mode(PgSslMode::Require);

    let pool = PgPoolOptions::new()
        .max_connections(MAX_CONNECTIONS)
        .acquire_timeout(ACQUIRE_TIMEOUT)
        .connect_with(options)
        .await
        .map_err(|e| DbError::Connection { detail: e.to_string() })?;

    let _ = DB_POOL.set(pool);
    Ok(())
}

fn project_ref(supabase_url: &str) -> Option<String> {
    let host = supabase_url.strip_prefix("https://").unwrap_or(supabase_url);
    let host = host.split('/').next()?;
    host.strip_suffix(".supabase.co").map(str::to_string)
}

fn pool() -> Result<&'static PgPool, DbError> {
    DB_POOL.get().ok_or(DbError::NotConnected)
}

fn query_err(e: sqlx::Error) -> DbError {
    DbError::Query {
        detail: e.to_string(),
    }
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn create_room(room_id: String) -> Result<ChatRoom, DbError> {
    let pool = pool()?;
    sqlx::query_as::<_, ChatRoom>(&format!(
        "INSERT INTO chat (room_id) VALUES ($1) RETURNING {ROOM_COLUMNS}"
    ))
    .bind(room_id)
    .fetch_one(pool)
    .await
    .map_err(query_err)
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn list_rooms() -> Result<Vec<ChatRoom>, DbError> {
    let pool = pool()?;
    let rows = sqlx::query_as::<_, ChatRoom>(&format!(
        "SELECT {ROOM_COLUMNS} FROM chat ORDER BY id"
    ))
    .fetch_all(pool)
    .await
    .map_err(query_err)?;
    Ok(rows)
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn get_room(room_id: String) -> Result<Option<ChatRoom>, DbError> {
    let pool = pool()?;
    let row = sqlx::query_as::<_, ChatRoom>(&format!(
        "SELECT {ROOM_COLUMNS} FROM chat WHERE room_id = $1"
    ))
    .bind(room_id)
    .fetch_optional(pool)
    .await
    .map_err(query_err)?;
    Ok(row)
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn set_room_offer(room_id: String, offer: String) -> Result<ChatRoom, DbError> {
    let pool = pool()?;
    sqlx::query_as::<_, ChatRoom>(&format!(
        "UPDATE chat SET offer = $2 WHERE room_id = $1 RETURNING {ROOM_COLUMNS}"
    ))
    .bind(&room_id)
    .bind(offer)
    .fetch_optional(pool)
    .await
    .map_err(query_err)?
    .ok_or(DbError::Query {
        detail: format!("chat row with room_id {room_id} not found"),
    })
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn set_room_answer(room_id: String, answer: String) -> Result<ChatRoom, DbError> {
    let pool = pool()?;
    sqlx::query_as::<_, ChatRoom>(&format!(
        "UPDATE chat SET answer = $2 WHERE room_id = $1 RETURNING {ROOM_COLUMNS}"
    ))
    .bind(&room_id)
    .bind(answer)
    .fetch_optional(pool)
    .await
    .map_err(query_err)?
    .ok_or(DbError::Query {
        detail: format!("chat row with room_id {room_id} not found"),
    })
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn update_room(id: i64, room_id: String) -> Result<ChatRoom, DbError> {
    let pool = pool()?;
    sqlx::query_as::<_, ChatRoom>(&format!(
        "UPDATE chat SET room_id = $1 WHERE id = $2 RETURNING {ROOM_COLUMNS}"
    ))
    .bind(room_id)
    .bind(id)
    .fetch_optional(pool)
    .await
    .map_err(query_err)?
    .ok_or(DbError::NotFound { id })
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn delete_room(id: i64) -> Result<bool, DbError> {
    let pool = pool()?;
    let result = sqlx::query("DELETE FROM chat WHERE id = $1")
        .bind(id)
        .execute(pool)
        .await
        .map_err(query_err)?;
    Ok(result.rows_affected() > 0)
}

#[cfg(test)]
mod tests {
    use super::project_ref;

    #[test]
    fn extracts_ref_from_project_url() {
        assert_eq!(
            project_ref("https://tahckcerodrjgzwvqmbi.supabase.co"),
            Some("tahckcerodrjgzwvqmbi".to_string())
        );
    }

    #[test]
    fn handles_trailing_slash() {
        assert_eq!(
            project_ref("https://tahckcerodrjgzwvqmbi.supabase.co/"),
            Some("tahckcerodrjgzwvqmbi".to_string())
        );
    }

    #[test]
    fn rejects_non_project_hosts() {
        assert_eq!(project_ref("https://example.com"), None);
        assert_eq!(project_ref("postgres://localhost"), None);
    }
}
