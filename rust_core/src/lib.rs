use std::sync::Mutex;

static COUNTER: Mutex<i32> = Mutex::new(0);

#[uniffi::export]
pub fn increment() -> i32 {
    let mut count = COUNTER.lock().unwrap();
    *count += 1;
    *count
}

#[uniffi::export]
pub fn get_count() -> i32 {
    *COUNTER.lock().unwrap()
}

uniffi::setup_scaffolding!();
