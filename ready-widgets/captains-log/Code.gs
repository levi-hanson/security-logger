/**
 * Captain's log: receives entries from the Ready Widgets app and adds each as a row.
 *
 * Paste this into the Google Sheet's Extensions → Apps Script, then
 * Deploy → New deployment → Web app, "Execute as: Me", "Who has access: Anyone".
 * Copy the web app URL (it ends in /exec) into the app under Captain's log.
 *
 * Anyone with that URL can add rows, so keep it to yourself.
 */
const SHEET_NAME = 'Log';

function doPost(e) {
  const lock = LockService.getScriptLock();
  lock.waitLock(15000);
  try {
    let body;
    try {
      body = JSON.parse(e.postData.contents);
    } catch (err) {
      return json_({ ok: false, error: 'not_json' });
    }
    const text = String(body.text || '').trim();
    if (!text) return json_({ ok: false, error: 'empty' });
    if (text.length > 20000) return json_({ ok: false, error: 'too_long' });

    const sheet = sheet_();
    const id = String(body.id || '');
    // The app resends an entry if it couldn't tell whether the first try landed; skip repeats.
    if (id) {
      const last = sheet.getLastRow();
      if (last > 1) {
        const n = Math.min(1000, last - 1);
        const ids = sheet.getRange(last - n + 1, 4, n, 1).getValues();
        for (let i = 0; i < ids.length; i++) {
          if (ids[i][0] === id) return json_({ ok: true, duplicate: true });
        }
      }
    }
    const at = body.at ? new Date(body.at) : new Date();
    sheet.appendRow([isNaN(at) ? new Date() : at, text, String(body.source || ''), id]);
    return json_({ ok: true });
  } finally {
    lock.releaseLock();
  }
}

/** Opening the URL in a browser shows this, which confirms the deployment works. */
function doGet() {
  return json_({ ok: true, app: 'captains-log' });
}

function sheet_() {
  const book = SpreadsheetApp.getActiveSpreadsheet();
  let sheet = book.getSheetByName(SHEET_NAME) ||
    book.getSheets().find((s) => String(s.getRange('A1').getValue()) === 'When');
  if (!sheet) {
    sheet = book.insertSheet(SHEET_NAME, 0);
    sheet.appendRow(['When', 'Entry', 'Source', 'Id']);
    sheet.setFrozenRows(1);
    sheet.getRange('A1:D1').setFontWeight('bold');
    sheet.getRange('A:A').setNumberFormat('yyyy-mm-dd h:mm am/pm');
    sheet.setColumnWidth(1, 160);
    sheet.setColumnWidth(2, 640);
    sheet.getRange('B:B').setWrap(true).setVerticalAlignment('top');
    sheet.hideColumns(4);
  }
  return sheet;
}

function json_(value) {
  return ContentService.createTextOutput(JSON.stringify(value)).setMimeType(ContentService.MimeType.JSON);
}
