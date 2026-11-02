// Root CLI 读取 SQLite 事件库，写入仍由独立 Java Binder 服务负责。
use libc::{c_char, c_int, c_void};
use serde_json::{Value, json};
use std::ffi::{CStr, CString};
use std::fs;
use std::io::{self, Write};
use std::path::Path;
use std::ptr;

type Db = c_void;
type Statement = c_void;
type Open = unsafe extern "C" fn(*const c_char, *mut *mut Db, c_int, *const c_char) -> c_int;
type Close = unsafe extern "C" fn(*mut Db) -> c_int;
type Prepare = unsafe extern "C" fn(
    *mut Db,
    *const c_char,
    c_int,
    *mut *mut Statement,
    *mut *const c_char,
) -> c_int;
type Bind = unsafe extern "C" fn(*mut Statement, c_int, i64) -> c_int;
type Step = unsafe extern "C" fn(*mut Statement) -> c_int;
type Number = unsafe extern "C" fn(*mut Statement, c_int) -> i64;
type Text = unsafe extern "C" fn(*mut Statement, c_int) -> *const u8;
type Error = unsafe extern "C" fn(*mut Db) -> *const c_char;
struct Api {
    library: *mut c_void,
    open: Open,
    close: Close,
    prepare: Prepare,
    bind: Bind,
    step: Step,
    finalize: Step,
    number: Number,
    text: Text,
    error: Error,
}
impl Api {
    fn load() -> io::Result<Self> {
        #[cfg(target_os = "android")]
        let name = c"libsqlite.so";
        #[cfg(not(target_os = "android"))]
        let name = c"libsqlite3.so.0";
        unsafe {
            let library = libc::dlopen(name.as_ptr(), libc::RTLD_NOW | libc::RTLD_LOCAL);
            if library.is_null() {
                return Err(io::Error::other("system SQLite library unavailable"));
            }
            macro_rules! symbol {
                ($name:literal, $signature:ty) => {{
                    let address = libc::dlsym(library, concat!($name, "\0").as_ptr().cast());
                    if address.is_null() {
                        libc::dlclose(library);
                        return Err(io::Error::other(concat!("missing SQLite API: ", $name)));
                    }
                    std::mem::transmute::<*mut c_void, $signature>(address)
                }};
            }
            Ok(Self {
                library,
                open: symbol!("sqlite3_open_v2", Open),
                close: symbol!("sqlite3_close_v2", Close),
                prepare: symbol!("sqlite3_prepare_v2", Prepare),
                bind: symbol!("sqlite3_bind_int64", Bind),
                step: symbol!("sqlite3_step", Step),
                finalize: symbol!("sqlite3_finalize", Step),
                number: symbol!("sqlite3_column_int64", Number),
                text: symbol!("sqlite3_column_text", Text),
                error: symbol!("sqlite3_errmsg", Error),
            })
        }
    }
}
impl Drop for Api {
    fn drop(&mut self) {
        unsafe {
            libc::dlclose(self.library);
        }
    }
}
struct Connection<'a> {
    api: &'a Api,
    db: *mut Db,
}
impl<'a> Connection<'a> {
    fn open(api: &'a Api, path: &Path, flags: i32) -> io::Result<Self> {
        let path = CString::new(path.as_os_str().as_encoded_bytes())?;
        let mut db = ptr::null_mut();
        let code = unsafe { (api.open)(path.as_ptr(), &mut db, flags, ptr::null()) };
        if code != 0 {
            if !db.is_null() {
                unsafe {
                    (api.close)(db);
                }
            }
            return Err(io::Error::other(format!(
                "open monitor database: SQLite {code}"
            )));
        }
        Ok(Self { api, db })
    }
    fn statement(&self, sql: &CStr) -> io::Result<Prepared<'_>> {
        let mut stmt = ptr::null_mut();
        let code =
            unsafe { (self.api.prepare)(self.db, sql.as_ptr(), -1, &mut stmt, ptr::null_mut()) };
        if code != 0 {
            return Err(self.error());
        }
        Ok(Prepared {
            connection: self,
            stmt,
        })
    }
    fn error(&self) -> io::Error {
        io::Error::other(
            unsafe { CStr::from_ptr((self.api.error)(self.db)) }
                .to_string_lossy()
                .into_owned(),
        )
    }
}
impl Drop for Connection<'_> {
    fn drop(&mut self) {
        unsafe {
            (self.api.close)(self.db);
        }
    }
}
struct Prepared<'a> {
    connection: &'a Connection<'a>,
    stmt: *mut Statement,
}
impl Prepared<'_> {
    fn bind(&self, index: i32, value: i64) -> io::Result<()> {
        if unsafe { (self.connection.api.bind)(self.stmt, index, value) } == 0 {
            Ok(())
        } else {
            Err(self.connection.error())
        }
    }
    fn number(&self, index: i32) -> i64 {
        unsafe { (self.connection.api.number)(self.stmt, index) }
    }
    fn text(&self, index: i32) -> String {
        let p = unsafe { (self.connection.api.text)(self.stmt, index) };
        if p.is_null() {
            String::new()
        } else {
            unsafe { CStr::from_ptr(p.cast()) }
                .to_string_lossy()
                .into_owned()
        }
    }
}
impl Drop for Prepared<'_> {
    fn drop(&mut self) {
        unsafe {
            (self.connection.api.finalize)(self.stmt);
        }
    }
}

pub fn rows(path: &Path, before: i64, after: i64, limit: usize) -> io::Result<Vec<Value>> {
    let api = Api::load()?;
    let connection = Connection::open(&api, path, 1)?;
    let stmt=connection.statement(c"SELECT id,timestamp,uid,pid,writer,package,path,func,status,flags FROM records WHERE (?1=0 OR id<?1) AND id>?2 AND uid>=0 AND uid<100000 AND uid!=1000 AND (path='/storage/emulated/0' OR path LIKE '/storage/emulated/0/%') ORDER BY id DESC LIMIT ?3")?;
    stmt.bind(1, before)?;
    stmt.bind(2, after)?;
    stmt.bind(3, limit as i64)?;
    let mut result = Vec::with_capacity(limit);
    loop {
        match unsafe { (api.step)(stmt.stmt) } {
            100 => {
                let op = stmt.text(7);
                let flags = stmt.number(9);
                let status = stmt.number(8);
                let operation = if op == "creat" || op.ends_with("insert") {
                    "CREATE"
                } else if op.ends_with("update") {
                    "UPDATE"
                } else {
                    "OPEN"
                };
                result.push(json!({"version":2,"sequence":stmt.number(0),"time_ms":stmt.number(1),
                    "uid":stmt.number(2),"pid":stmt.number(3),"process":stmt.text(4),"caller":stmt.text(5),
                    "path":stmt.text(6),"operation":operation,"result":if flags&1!=0 || flags&32!=0{0}else{-1},
                    "errno":status.abs(),"method":if op.starts_with("media_") || op.starts_with("download_"){"binder_uid"}else{"fuse_uid"},"confidence":"high",
                    "extra":format!("op={op}|file_monitor_flags={flags}|native_status={status}")}));
            }
            101 => break,
            _ => return Err(connection.error()),
        }
    }
    Ok(result)
}

pub fn run(module: &Path, before: i64, after: i64, limit: usize) -> io::Result<()> {
    let health: Value = fs::read("/dev/srx_file_monitor_health.json")
        .ok()
        .and_then(|bytes| serde_json::from_slice(&bytes).ok())
        .unwrap_or_else(|| {
            json!({
                "enabled": true,
                "lost": 0,
                "write_errors": 0,
                "updated_ms": 0
            })
        });
    let updated = if health["connected"].as_bool() == Some(false)
        && health["enabled"].as_bool() != Some(false)
    {
        0
    } else {
        health["updated_ms"].as_u64().unwrap_or(0)
    };
    let file = module.join("logs/monitor/file_monitor.db");
    let entries = if file.exists() {
        rows(&file, before, after, limit + 1)?
    } else {
        Vec::new()
    };
    let stdout = io::stdout();
    let mut stdout = stdout.lock();
    writeln!(
        stdout,
        "{}",
        json!({"version":2,"enabled":health["enabled"].as_bool().unwrap_or(true),"has_more":entries.len()>limit,"lost":health["lost"].as_u64().unwrap_or(0),
        "write_errors":health["write_errors"].as_u64().unwrap_or(0),"updated_ms":updated})
    )?;
    for entry in entries.iter().take(limit) {
        writeln!(stdout, "{entry}")?;
    }
    Ok(())
}

pub fn clear(module_dir: &Path) -> io::Result<()> {
    let file = module_dir.join("logs/monitor/file_monitor.db");
    if !file.exists() {
        return Ok(());
    }
    let api = Api::load()?;
    let connection = Connection::open(&api, &file, 2)?;
    let statement = connection.statement(c"DELETE FROM records")?;
    if unsafe { (api.step)(statement.stmt) } != 101 {
        return Err(connection.error());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sqlite_pages_are_lossless_and_scope_is_primary_storage() {
        let path = std::env::temp_dir().join(format!("srx-sqlite-{}.db", std::process::id()));
        let api = Api::load().unwrap();
        let mut db = ptr::null_mut();
        let filename = CString::new(path.as_os_str().as_encoded_bytes()).unwrap();
        assert_eq!(
            unsafe { (api.open)(filename.as_ptr(), &mut db, 6, ptr::null()) },
            0
        );
        let connection = Connection { api: &api, db };
        let schema = connection.statement(c"CREATE TABLE records(id INTEGER PRIMARY KEY AUTOINCREMENT,timestamp BIGINT,uid INT,pid INT,writer TEXT,package TEXT,path TEXT,func TEXT,status INT,flags INT)").unwrap();
        assert_eq!(unsafe { (api.step)(schema.stmt) }, 101);
        drop(schema);
        for i in 0..250 {
            let insert = connection.statement(c"INSERT INTO records(timestamp,uid,pid,writer,package,path,func,status,flags) VALUES(?1,10234,88,'com.android.providers.media.module','example.app','/storage/emulated/0/a|b.txt','open',0,1)").unwrap();
            insert.bind(1, 1000 + i).unwrap();
            assert_eq!(unsafe { (api.step)(insert.stmt) }, 101);
        }
        let excluded = connection.statement(c"INSERT INTO records VALUES(251,0,1010234,88,'writer','app','/storage/emulated/10/x','open',0,1)").unwrap();
        assert_eq!(unsafe { (api.step)(excluded.stmt) }, 101);
        drop(excluded);
        drop(connection);
        let page = rows(&path, 0, 0, 100).unwrap();
        assert_eq!(page.len(), 100);
        assert_eq!(page[0]["sequence"], 250);
        let older = rows(
            &path,
            page.last().unwrap()["sequence"].as_i64().unwrap(),
            0,
            500,
        )
        .unwrap();
        assert_eq!(older.len(), 150);
        assert_eq!(page[0]["caller"], "example.app");
        assert_eq!(page[0]["operation"], "OPEN");
        assert_eq!(page[0]["path"], "/storage/emulated/0/a|b.txt");
        assert!(rows(&path, 0, 250, 100).unwrap().is_empty());
        fs::remove_file(path).unwrap();
    }
}
