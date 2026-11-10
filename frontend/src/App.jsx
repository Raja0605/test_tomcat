import { useCallback, useEffect, useRef, useState } from 'react';
import axios from 'axios';

const api = axios.create({ baseURL: import.meta.env.VITE_API_BASE_URL || '/api' });
const humanSize = (bytes) => {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
};
const friendlyError = (error) =>
  error.response?.data?.error || error.message || 'The request could not be completed.';

export default function App() {
  const [documents, setDocuments] = useState([]);
  const [tests, setTests] = useState([]);
  const [permissionNote, setPermissionNote] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [progress, setProgress] = useState(0);
  const [busy, setBusy] = useState(false);
  const fileInput = useRef(null);

  const refreshDocuments = useCallback(async () => {
    setError('');
    try {
      const { data } = await api.get('/documents');
      setDocuments(data);
    } catch (requestError) {
      setError(friendlyError(requestError));
    }
  }, []);

  const runPermissionChecks = async () => {
    setError('');
    setBusy(true);
    try {
      const { data } = await api.get('/documents/permission-check');
      setTests(data.tests);
      setPermissionNote(data.note);
    } catch (requestError) {
      setError(friendlyError(requestError));
    } finally {
      setBusy(false);
    }
  };

  useEffect(() => {
    refreshDocuments();
  }, [refreshDocuments]);

  const upload = async (event) => {
    event.preventDefault();
    const file = fileInput.current?.files?.[0];
    if (!file) {
      setError('Choose a PDF or TXT file first.');
      return;
    }
    setError('');
    setNotice('');
    setProgress(0);
    setBusy(true);
    const body = new FormData();
    body.append('file', file);
    try {
      const { data } = await api.post('/documents/upload', body, {
        onUploadProgress: (eventProgress) => {
          if (eventProgress.total) {
            setProgress(Math.round((eventProgress.loaded * 100) / eventProgress.total));
          }
        },
      });
      setNotice(`Uploaded ${data.filename}.`);
      event.target.reset();
      await refreshDocuments();
    } catch (requestError) {
      setError(friendlyError(requestError));
    } finally {
      setBusy(false);
    }
  };

  const createTestFile = async () => {
    setError('');
    setNotice('');
    setBusy(true);
    try {
      const { data } = await api.post('/documents/create-test');
      setNotice(`Created ${data.filename}.`);
      await refreshDocuments();
    } catch (requestError) {
      setError(friendlyError(requestError));
    } finally {
      setBusy(false);
    }
  };

  const documentUrl = (url) => `${window.location.origin}${url}`;

  return (
    <main className="page">
      <header className="topbar">
        <div>
          <p className="eyebrow">OPERATIONS / STORAGE</p>
          <h1>Document permission test</h1>
          <p className="subtitle">Verify document uploads and shared access between Tomcat and Nginx.</p>
        </div>
        <span className="server-tag"><span className="pulse" /> Tomcat 9 · Linux</span>
      </header>

      {error && <div className="alert error" role="alert">{error}</div>}
      {notice && <div className="alert success" role="status">{notice}</div>}

      <section className="card upload-card">
        <div className="section-heading">
          <div><p className="eyebrow">DOCUMENTS</p><h2>Upload a document</h2></div>
          <span className="limit-label">PDF or TXT · Up to 10 MB</span>
        </div>
        <form className="upload-form" onSubmit={upload}>
          <label className="file-picker">
            <span className="file-icon">↑</span>
            <span><strong>Choose a file</strong><small>PDF or plain text</small></span>
            <input ref={fileInput} type="file" accept=".pdf,.txt,application/pdf,text/plain" />
          </label>
          <button className="button primary" disabled={busy} type="submit">{busy ? 'Working…' : 'Upload file'}</button>
        </form>
        {progress > 0 && <div className="progress-wrap" aria-label={`Upload ${progress}% complete`}>
          <div className="progress-track"><div className="progress-fill" style={{ width: `${progress}%` }} /></div>
          <span>{progress}%</span>
        </div>}
      </section>

      <section className="card">
        <div className="section-heading">
          <div><p className="eyebrow">PHOTO PATH</p><h2>Stored documents <span className="count">{documents.length}</span></h2></div>
          <div className="actions">
            <button className="button secondary" onClick={refreshDocuments} disabled={busy}>↻ Refresh</button>
            <button className="button primary" onClick={createTestFile} disabled={busy}>＋ Create test file</button>
          </div>
        </div>
        {documents.length === 0 ? <div className="empty">
          <span className="empty-icon">▤</span><strong>No documents yet</strong>
          <span>Upload a document or create a test file to get started.</span>
        </div> : <div className="table-scroll">
          <table>
            <thead><tr><th>FILE NAME</th><th>SIZE</th><th>UPLOAD DATE</th><th className="align-right">ACTION</th></tr></thead>
            <tbody>{documents.map((document) => <tr key={document.filename}>
              <td><span className="document-icon">{document.filename.toLowerCase().endsWith('.pdf') ? 'PDF' : 'TXT'}</span><strong>{document.filename}</strong></td>
              <td>{humanSize(document.size)}</td>
              <td>{new Date(document.uploadedAt).toLocaleString()}</td>
              <td className="align-right"><a className="button small secondary" href={documentUrl(document.url)} target="_blank" rel="noreferrer">View / download ↗</a></td>
            </tr>)}</tbody>
          </table>
        </div>}
      </section>

      <section className="card permission-card">
        <div className="section-heading">
          <div><p className="eyebrow">ACCESS VERIFICATION</p><h2>Permission tests</h2></div>
          <button className="button secondary" onClick={runPermissionChecks} disabled={busy}>Run permission tests</button>
        </div>
        <div className="test-intro">
          The backend checks its own file access and checks POSIX mode bits for Nginx-compatible access.
          It does not execute commands as, or impersonate, <code>www-data</code>.
        </div>
        {tests.length === 0 ? <div className="test-placeholder">Run the tests to check backend access and current file permissions.</div>
          : <div className="test-list">{tests.map((test) => <div className="test-row" key={test.id}>
            <span className={`status ${test.status.toLowerCase()}`}>{test.status}</span>
            <div><strong>{test.label}</strong><p>{test.details}</p></div>
          </div>)}</div>}
        {permissionNote && <div className="permission-note">{permissionNote}</div>}
        <div className="admin-note">
          <strong>Administrator verification required</strong>
          <span>Confirm actual Nginx access and write restrictions with the <code>sudo -u www-data</code> commands in the README.</span>
        </div>
      </section>
      <footer>Files are stored in <code>/var/www/openrecruiter.shartechnologies.com/html/PhotoPath</code></footer>
    </main>
  );
}
