mod database;

use std::sync::{Mutex, OnceLock};

#[derive(Debug, Clone)]
struct Config {
    supabase_db_password: String,
    supabase_url: String,
    supabase_key: String
}

static COUNTER: Mutex<i32> = Mutex::new(0);
static CONFIG: OnceLock<Config> = OnceLock::new();

#[uniffi::export]
pub fn init(supabase_db_password: String, supabase_url: String, supabase_key: String) {
    CONFIG.set(Config { 
        supabase_db_password,
        supabase_key,
        supabase_url
     }).unwrap()
}

pub(crate) fn config() -> Option<&'static Config> {
    CONFIG.get()
}

#[uniffi::export]
pub fn increment() -> i32 {
    let mut count = COUNTER.lock().unwrap();
    *count += 2;
    *count
}

#[uniffi::export]
pub fn get_count() -> i32 {
    *COUNTER.lock().unwrap()
}

uniffi::setup_scaffolding!();
