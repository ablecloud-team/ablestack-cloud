/*
 * noVNC: HTML5 VNC client
 * Licensed under MPL 2.0 (see LICENSE.txt)
 */

const INITIAL_CONNECT_TIMEOUT_MILLIS = 10000;

export default class MoldConsoleConnection {
    constructor(browser) {
        this.browser = browser;
        const hash = new URLSearchParams(browser.location.hash.slice(1));
        const attempt = hash.get('moldConsoleAttempt');
        this.attempt = attempt === '1' || attempt === '2' ? attempt : null;
        this.openerOrigin = null;
        this.timeout = null;

        if (this.attempt === '1') {
            const candidate = hash.get('moldConsoleOpener');
            if (candidate) {
                try {
                    const target = new URL(candidate);
                    if (target.origin === candidate && ['https:', 'http:'].includes(target.protocol)) {
                        this.openerOrigin = target.origin;
                    }
                } catch {
                    // An invalid origin cannot be used for messaging.
                }
            }
        }
    }

    get canRetry() {
        return this.attempt === '1' && this.openerOrigin !== null &&
            !!this.browser.opener && !this.browser.opener.closed;
    }

    start(onTimeout) {
        if (this.attempt !== null) {
            this.timeout = this.browser.setTimeout(onTimeout, INITIAL_CONNECT_TIMEOUT_MILLIS);
        }
    }

    finish(failed = false) {
        this.browser.clearTimeout(this.timeout);
        const notifyOpener = this.canRetry;
        this.attempt = null;
        if (notifyOpener) {
            this.browser.opener.postMessage(failed ? 'mold:console-connect-failed' : 'mold:console-connect-finished', this.openerOrigin);
        }
        return notifyOpener && failed;
    }
}
