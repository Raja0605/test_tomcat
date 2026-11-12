# Document Permission Test

A small document upload/download dashboard for checking file access between a Java 17 application deployed as a WAR to external Apache Tomcat 9 (running as `tomcat9`) and Nginx. Uploaded documents are stored at the fixed Linux path:

`/var/www/openrecruiter.shartechnologies.com/html/PhotoPath`

The backend storage path is configured by `document.storage-directory` in `backend/src/main/resources/application.properties`.

Documents are served by Nginx from `/PhotoPath/<filename>`; the Spring API does not stream document contents. The dashboard permission checks inspect backend operations and POSIX mode bits. They do **not** impersonate `www-data` or prove that Nginx can access a file; an administrator must run the verification commands below.

## Project layout

- `backend/` — Spring Boot 2.7 WAR, Java 17, external Tomcat 9 / `javax.servlet`
- `frontend/` — React and Vite dashboard
- `deploy/nginx-document-permission-test.conf` — Nginx frontend, API proxy, and static document location

## Requirements

- Linux host with Java 17, Maven, Apache Tomcat 9, Nginx, and Node.js/npm
- Tomcat service account named `tomcat9`; Nginx worker account named `www-data`
- TLS termination for the production hostname (configure your site's HTTPS certificate in Nginx)

## Prepare storage permissions

Use an administrator account. The application never changes ownership and does not run shell commands.

```bash
sudo install -d -o tomcat9 -g tomcat9 -m 0755 \
  /var/www/openrecruiter.shartechnologies.com/html/PhotoPath
sudo namei -l /var/www/openrecruiter.shartechnologies.com/html/PhotoPath
```

All parent directories must be searchable by `www-data`. Ensure the `html` and `PhotoPath` directory modes allow traversal (commonly `0755`). New files are created with mode `0644` at creation time (subject to the Tomcat process umask), owned by the Tomcat process; the application does not change ownership. Do not use `chmod 777`.

If existing files need a one-time permission correction, an administrator can review them and apply a least-privilege mode such as `0644`; avoid recursive ownership changes unless they are specifically required and reviewed.

## Build and deploy the backend

```bash
cd backend
mvn clean package
sudo install -o tomcat9 -g tomcat9 -m 0644 \
  target/document-permission-test.war \
  /var/lib/tomcat9/webapps/document-permission-test.war
sudo systemctl restart tomcat9
sudo systemctl status tomcat9 --no-pager
```

The WAR is named `document-permission-test.war`, so Tomcat exposes the API beneath `/document-permission-test`. Confirm Tomcat's service is configured to run as `tomcat9`. Review Tomcat logs if startup fails:

```bash
sudo journalctl -u tomcat9 -n 100 --no-pager
```

The storage directory is created at application startup if it does not exist. Create it beforehand as shown above to ensure predictable owner and directory permissions.

## Build and deploy the frontend

```bash
cd frontend
npm install
npm run build
sudo install -d -o root -g root -m 0755 \
  /var/www/openrecruiter.shartechnologies.com/html/app
sudo cp -a dist/. /var/www/openrecruiter.shartechnologies.com/html/app/
```

For local frontend development, run `npm run dev`. Vite proxies `/api` to `http://localhost:8080`, which is convenient when running the Spring app locally with Maven. In production, the browser sends `/api` requests to Nginx.

## Configure Nginx

Copy `deploy/nginx-document-permission-test.conf` into the Nginx site's configuration directory. Replace `your-domain.com` with the real hostname and add your site's TLS `listen 443 ssl` and certificate directives. The included server listens on port 80 for a simple starting configuration.

The API proxy maps `/api/...` to Tomcat's `/document-permission-test/api/...`. The `/PhotoPath/` alias exposes only the fixed document directory; directory listings are disabled and only GET/HEAD are allowed.

```bash
sudo nginx -t
sudo systemctl reload nginx
```

Example direct document URL:

`https://your-domain.com/PhotoPath/upload-<uuid>.pdf`

## API

- `POST /api/documents/upload` — multipart field `file`; accepts PDF or TXT up to 10 MB. PDF requires `application/pdf` and a `%PDF-` signature; TXT requires `text/plain` and rejects NUL bytes.
- `GET /api/documents` — list document metadata.
- `GET /api/documents/{filename}` — retrieve metadata only.
- `POST /api/documents/create-test` — create a TXT file containing the timestamp and Tomcat process username.
- `DELETE /api/documents/{filename}` — delete the named document only.
- `GET /api/documents/permission-check` — create/read a test file and report backend checks plus POSIX mode-bit checks.

The API only resolves generated filenames in the fixed storage directory, rejects symlinks for file operations, and never accepts a filesystem path from a request. File downloads use Nginx; the metadata endpoint does not return file bytes.

## Test permissions as an administrator

Upload a file and run the dashboard's permission tests. A PASS for Nginx means the mode bits are compatible with access by a non-owner: files are world-readable, parent directories are traversable, and group/other write bits are absent on the file and storage directory. This is a conservative mode-bit check—not an OS-level access check for the `www-data` account. ACLs, mount options, Nginx configuration, and other policies can still affect real access.

An administrator should verify a specific uploaded filename (substitute it below):

```bash
FILE='upload-<uuid>.pdf'
PATH_TO_FILE="/var/www/openrecruiter.shartechnologies.com/html/PhotoPath/$FILE"

# Confirm each directory component is traversable and inspect file modes.
namei -l "$PATH_TO_FILE"
stat -c '%A %a %U:%G %n' \
  /var/www/openrecruiter.shartechnologies.com/html/PhotoPath "$PATH_TO_FILE"

# Nginx account can read the file.
sudo -u www-data test -r "$PATH_TO_FILE" \
  && echo 'PASS: www-data can read' \
  || echo 'FAIL: www-data cannot read'

# Nginx account cannot modify the file or delete entries in the directory.
sudo -u www-data test ! -w "$PATH_TO_FILE" \
  && echo 'PASS: www-data cannot write the file' \
  || echo 'FAIL: www-data can write the file'
sudo -u www-data test ! -w /var/www/openrecruiter.shartechnologies.com/html/PhotoPath \
  && echo 'PASS: www-data cannot delete/create directory entries' \
  || echo 'FAIL: www-data can modify directory entries'

# Verify the same behavior for a file that existed before a new upload.
EXISTING_FILE='replace-with-an-existing-filename.txt'
sudo -u www-data test -r \
  "/var/www/openrecruiter.shartechnologies.com/html/PhotoPath/$EXISTING_FILE" \
  && echo 'PASS: www-data can read existing file' \
  || echo 'FAIL: www-data cannot read existing file'
```

To confirm Tomcat runs as the expected user and can read/create files:

```bash
ps -eo user,comm,args | grep '[t]omcat'
sudo -u tomcat9 test -r "$PATH_TO_FILE" \
  && echo 'PASS: tomcat9 can read' \
  || echo 'FAIL: tomcat9 cannot read'
```

The permission dashboard also checks backend create/read automatically. Its new-upload check requires at least one uploaded file, and the existing-file check requires a file that existed before the check started. Upload a document, refresh, then rerun the tests. These checks report FAIL when their prerequisite file does not exist.

## Frontend behavior

The dashboard supports upload progress, document listing, upload timestamps and sizes, direct Nginx view/download links, test-file creation, refresh, explicit API errors, and permission checks. API error responses contain an `error` message; the UI displays it above the current section.

cherry pick one
cherry pick two
git stash cmd 
