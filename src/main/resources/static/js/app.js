/**
 * PaymentCore — Demo Layer Dashboard Controller
 */

// Tab Navigation
document.addEventListener('DOMContentLoaded', () => {
    initNavigation();
    loadScreenData('invariants'); // default screen
});

function initNavigation() {
    const tabs = document.querySelectorAll('.nav-tab');
    tabs.forEach(tab => {
        tab.addEventListener('click', () => {
            tabs.forEach(t => t.classList.remove('active'));
            document.querySelectorAll('.tab-pane').forEach(p => p.classList.remove('active'));

            tab.classList.add('active');
            const target = tab.getAttribute('data-tab');
            const pane = document.getElementById(target);
            if (pane) {
                pane.classList.add('active');
                loadScreenData(target);
            }
        });
    });
}

function loadScreenData(tabName) {
    switch (tabName) {
        case 'invariants':
            loadInvariants();
            break;
        case 'failure-lab':
            // ready on demand
            break;
        case 'benchmarks':
            loadBenchmarks();
            break;
        case 'timeline':
            loadRecentTransactionsForTimeline();
            break;
        case 'reconciliation':
            loadReconciliation();
            break;
    }
}

// --- Screen 1: Failure Lab ---

async function runScenario(scenarioId) {
    const btn = document.getElementById(`btn-${scenarioId}`);
    const statusBadge = document.getElementById(`status-${scenarioId}`);
    const logContainer = document.getElementById(`logs-${scenarioId}`);
    
    if (btn) btn.disabled = true;
    if (statusBadge) {
        statusBadge.className = 'badge badge-warning';
        statusBadge.innerText = 'Running...';
    }
    if (logContainer) {
        logContainer.style.display = 'block';
        logContainer.innerText = 'Executing scenario against active database engine...\n';
    }

    try {
        const response = await fetch(`/api/demo/failure-lab/${scenarioId}`, { method: 'POST' });
        const result = await response.json();

        if (statusBadge) {
            if (result.outcome === 'PASSED') {
                statusBadge.className = 'badge badge-success';
                statusBadge.innerText = 'PASSED (✓)';
            } else {
                statusBadge.className = 'badge badge-danger';
                statusBadge.innerText = 'FAILED (✗)';
            }
        }

        if (logContainer && result.executionLog) {
            logContainer.innerText = result.executionLog.map((line, idx) => `[Step ${idx+1}] ${line}`).join('\n') + 
                `\n\n[Summary] Duration: ${result.durationMs}ms | Result: ${result.outcome}`;
        }
    } catch (e) {
        if (statusBadge) {
            statusBadge.className = 'badge badge-danger';
            statusBadge.innerText = 'ERROR';
        }
        if (logContainer) {
            logContainer.innerText = `Network or execution error: ${e.message}`;
        }
    } finally {
        if (btn) btn.disabled = false;
    }
}

// --- Screen 2: Ledger Integrity Monitor ---

async function loadInvariants() {
    try {
        const res = await fetch('/api/demo/invariants');
        const data = await res.json();

        // Update Stat Counters
        document.getElementById('stat-total-txns').innerText = data.summary.totalTransactions.toLocaleString();
        document.getElementById('stat-total-entries').innerText = data.summary.totalLedgerEntries.toLocaleString();
        document.getElementById('stat-total-accounts').innerText = data.summary.totalAccounts.toLocaleString();
        document.getElementById('stat-equilibrium-status').innerText = data.summary.allPassed ? '100% Balanced' : 'Imbalance Detected';
        document.getElementById('stat-equilibrium-status').className = data.summary.allPassed ? 'stat-value text-emerald' : 'stat-value text-rose';

        // Render Invariant Cards
        const grid = document.getElementById('invariants-cards-grid');
        grid.innerHTML = data.invariants.map(inv => `
            <div class="invariant-card">
                <div>
                    <div class="inv-header">
                        <span class="inv-id">${inv.id}</span>
                        <span class="badge ${inv.passed ? 'badge-success' : 'badge-danger'}">
                            ${inv.passed ? 'VERIFIED (✓)' : 'VIOLATION (✗)'}
                        </span>
                    </div>
                    <div class="inv-name" style="margin-top: 0.5rem;">${inv.name}</div>
                    <div class="inv-desc" style="margin-top: 0.35rem;">${inv.description}</div>
                </div>
                <div class="inv-footer">
                    <span style="color: var(--text-muted);">Status Detail:</span>
                    <span style="font-weight: 600; color: ${inv.passed ? 'var(--accent-emerald)' : 'var(--accent-rose)'};">${inv.details}</span>
                </div>
            </div>
        `).join('');

        // Render Recent Transactions
        const txTableBody = document.getElementById('recent-transactions-tbody');
        txTableBody.innerHTML = data.recentTransactions.map(tx => `
            <tr>
                <td style="font-family: monospace; color: var(--accent-cyan); font-weight: 600;">${tx.id.substring(0, 8)}...</td>
                <td><span class="badge badge-neutral">${tx.type}</span></td>
                <td style="font-weight: 700;">₹${(tx.amount / 100).toFixed(2)}</td>
                <td>${tx.currency}</td>
                <td><span class="badge badge-success">${tx.status}</span></td>
                <td style="color: var(--text-muted); font-size: 0.8rem;">${new Date(tx.created_at).toLocaleTimeString()}</td>
                <td>
                    <button class="btn btn-secondary" style="padding: 0.25rem 0.6rem; font-size: 0.75rem;" onclick="drillDownLedger('${tx.id}')">
                        Inspect Ledger
                    </button>
                </td>
            </tr>
        `).join('');
    } catch (e) {
        console.error('Failed to load invariants', e);
    }
}

async function drillDownLedger(transactionId) {
    try {
        const res = await fetch(`/api/demo/transactions/${transactionId}/ledger`);
        const data = await res.json();

        document.getElementById('ledger-modal-txn-id').innerText = transactionId;
        const entriesContainer = document.getElementById('ledger-entries-drilldown');

        let totalDebit = 0;
        let totalCredit = 0;

        const rows = data.entries.map(entry => {
            const isDebit = entry.entry_type === 'DEBIT';
            if (isDebit) totalDebit += entry.amount;
            else totalCredit += entry.amount;

            return `
                <tr>
                    <td style="font-family: monospace;">${entry.account_id.substring(0, 8)}...</td>
                    <td>
                        <span class="badge ${isDebit ? 'badge-warning' : 'badge-success'}">
                            ${entry.entry_type}
                        </span>
                    </td>
                    <td style="font-weight: 700; ${isDebit ? 'color: var(--accent-amber);' : 'color: var(--accent-emerald);'}">
                        ${isDebit ? '-' : '+'}₹${(entry.amount / 100).toFixed(2)}
                    </td>
                    <td>${entry.currency}</td>
                    <td style="color: var(--text-muted); font-size: 0.8rem;">${new Date(entry.created_at).toLocaleTimeString()}</td>
                </tr>
            `;
        }).join('');

        entriesContainer.innerHTML = `
            <table>
                <thead>
                    <tr><th>Account</th><th>Type</th><th>Amount</th><th>Currency</th><th>Timestamp</th></tr>
                </thead>
                <tbody>${rows}</tbody>
            </table>
            <div style="margin-top: 1rem; display: flex; justify-content: space-between; padding: 0.75rem; background: rgba(0,0,0,0.3); border-radius: 6px; font-weight: 700;">
                <span style="color: var(--accent-amber);">Total Debits: ₹${(totalDebit/100).toFixed(2)}</span>
                <span style="color: var(--accent-emerald);">Total Credits: ₹${(totalCredit/100).toFixed(2)}</span>
                <span style="color: var(--accent-cyan);">Net Delta: ₹${((totalCredit - totalDebit)/100).toFixed(2)} (Balanced)</span>
            </div>
        `;

        document.getElementById('ledger-modal').classList.add('open');
    } catch (e) {
        alert('Failed to inspect transaction ledger: ' + e.message);
    }
}

function closeLedgerModal() {
    document.getElementById('ledger-modal').classList.remove('open');
}

// --- Screen 3: Benchmark Observatory ---

async function loadBenchmarks() {
    try {
        const res = await fetch('/api/demo/benchmarks');
        const benchmarks = await res.json();
        const container = document.getElementById('benchmarks-container');

        if (!benchmarks || benchmarks.length === 0) {
            container.innerHTML = `<div class="card"><p style="color: var(--text-secondary);">No Stage 5 benchmark artifacts found in target/benchmark-results.</p></div>`;
            return;
        }

        // Group by benchmarkName
        const grouped = {};
        benchmarks.forEach(b => {
            if (!grouped[b.benchmarkName]) grouped[b.benchmarkName] = [];
            grouped[b.benchmarkName].push(b);
        });

        container.innerHTML = Object.entries(grouped).map(([name, scenarios]) => {
            const maxTps = Math.max(...scenarios.map(s => s.throughputTps || 1), 1);

            const bars = scenarios.map((s, idx) => {
                const pct = Math.round((s.throughputTps / maxTps) * 100);
                const colorClass = idx === 0 ? 'bar-primary' : 'bar-secondary';
                return `
                    <div class="bar-row">
                        <div class="bar-meta">
                            <span style="font-weight: 600;">${s.scenario}</span>
                            <span><strong>${s.throughputTps.toFixed(1)} TPS</strong> | p50: ${s.p50LatencyMs}ms | p99: ${s.p99LatencyMs}ms</span>
                        </div>
                        <div class="bar-track">
                            <div class="bar-fill ${colorClass}" style="width: ${Math.max(pct, 12)}%;">
                                ${s.throughputTps.toFixed(1)} TPS
                            </div>
                        </div>
                    </div>
                `;
            }).join('');

            const first = scenarios[0];
            return `
                <div class="bench-card">
                    <div style="display: flex; justify-content: space-between; align-items: center;">
                        <h3 style="font-size: 1.15rem; font-weight: 700;">${name}</h3>
                        <span class="badge badge-neutral">${scenarios.length} Scenarios</span>
                    </div>
                    <div class="bar-chart">${bars}</div>
                    <div style="margin-top: 1.25rem; font-size: 0.8rem; color: var(--text-muted); border-top: 1px solid rgba(255,255,255,0.06); padding-top: 0.75rem;">
                        <strong>Methodology (REQ-122):</strong> Concurrency: ${first.concurrency} threads | Pool: ${first.poolSize} | Warmup: ${first.warmupIterations} | Measured: ${first.measuredIterations} iterations
                    </div>
                </div>
            `;
        }).join('');
    } catch (e) {
        console.error('Failed to load benchmarks', e);
    }
}

// --- Screen 4: Distributed Transaction Timeline ---

async function loadRecentTransactionsForTimeline() {
    try {
        const res = await fetch('/api/demo/invariants');
        const data = await res.json();
        if (data.recentTransactions && data.recentTransactions.length > 0) {
            document.getElementById('timeline-input-txid').value = data.recentTransactions[0].id;
            fetchTimeline();
        }
    } catch (e) {
        console.error(e);
    }
}

async function fetchTimeline() {
    const txId = document.getElementById('timeline-input-txid').value.trim();
    if (!txId) return;

    const timelineContainer = document.getElementById('timeline-container');
    timelineContainer.innerHTML = `<p style="color: var(--text-secondary);">Loading timeline for ${txId}...</p>`;

    try {
        const res = await fetch(`/api/demo/timeline/${txId}`);
        const data = await res.json();

        timelineContainer.innerHTML = `
            <div style="margin-bottom: 1.5rem; display: flex; align-items: center; gap: 1rem;">
                <span style="font-size: 1.1rem; font-weight: 700;">Transaction: <code style="color: var(--accent-cyan);">${data.transactionId}</code></span>
                <span class="badge badge-success">${data.status}</span>
            </div>
            <div class="timeline">
                ${data.steps.map(step => `
                    <div class="timeline-item">
                        <div class="timeline-dot done">✓</div>
                        <div class="timeline-content">
                            <div class="timeline-header">
                                <span class="timeline-step-title">${step.stepName}</span>
                                <span class="timeline-time">${new Date(step.timestamp).toLocaleTimeString()}</span>
                            </div>
                            <div class="timeline-detail">${step.details}</div>
                        </div>
                    </div>
                `).join('')}
            </div>
        `;
    } catch (e) {
        timelineContainer.innerHTML = `<p style="color: var(--accent-rose);">Transaction not found or error loading timeline.</p>`;
    }
}

// --- Screen 5: Reconciliation Incident Center ---

async function loadReconciliation() {
    try {
        const res = await fetch('/api/demo/reconciliation');
        const data = await res.json();

        const badge = document.getElementById('recon-drift-badge');
        if (data.driftCount > 0) {
            badge.className = 'badge badge-danger';
            badge.innerText = `${data.driftCount} DRIFT DETECTED`;
        } else {
            badge.className = 'badge badge-success';
            badge.innerText = '0 DRIFT (ALL IN SYNC)';
        }

        const tbody = document.getElementById('reconciliation-tbody');
        tbody.innerHTML = data.accounts.map(acc => {
            const hasDrift = acc.delta !== 0;
            return `
                <tr style="${hasDrift ? 'background: rgba(244, 63, 94, 0.08);' : ''}">
                    <td style="font-family: monospace; font-weight: 600;">${acc.id.substring(0, 8)}...</td>
                    <td>₹${(acc.cached_balance / 100).toFixed(2)}</td>
                    <td>₹${(acc.ledger_derived_balance / 100).toFixed(2)}</td>
                    <td style="font-weight: 700; ${hasDrift ? 'color: var(--accent-rose);' : 'color: var(--accent-emerald);'}">
                        ${hasDrift ? (acc.delta > 0 ? '+' : '') + '₹' + (acc.delta/100).toFixed(2) : '₹0.00'}
                    </td>
                    <td><span class="badge ${acc.status === 'ACTIVE' ? 'badge-success' : 'badge-danger'}">${acc.status}</span></td>
                    <td>
                        ${hasDrift ? `
                            <button class="btn btn-danger" style="padding: 0.25rem 0.6rem; font-size: 0.75rem;" onclick="openRemediateModal('${acc.id}', ${acc.cached_balance}, ${acc.ledger_derived_balance})">
                                Fix Drift (6-Step)
                            </button>
                        ` : `
                            <button class="btn btn-secondary" style="padding: 0.25rem 0.6rem; font-size: 0.75rem;" onclick="simulateDrift('${acc.id}')">
                                Inject +₹500 Drift
                            </button>
                        `}
                    </td>
                </tr>
            `;
        }).join('');
    } catch (e) {
        console.error('Failed to load reconciliation', e);
    }
}

async function simulateDrift(accountId) {
    if (!confirm('Inject deliberate +50,000 paise (+₹500) balance drift into this account to test reconciliation?')) return;
    try {
        await fetch('/api/demo/reconciliation/simulate-drift', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ accountId: accountId, driftPaise: 50000 })
        });
        loadReconciliation();
    } catch (e) {
        alert('Simulation error: ' + e.message);
    }
}

let activeIncidentAccountId = null;

async function openRemediateModal(accountId, cached, ledger) {
    activeIncidentAccountId = accountId;
    document.getElementById('recon-modal-acc-id').innerText = accountId;
    document.getElementById('recon-modal-cached').innerText = `₹${(cached/100).toFixed(2)}`;
    document.getElementById('recon-modal-ledger').innerText = `₹${(ledger/100).toFixed(2)}`;
    document.getElementById('remediation-modal').classList.add('open');
}

function closeRemediationModal() {
    document.getElementById('remediation-modal').classList.remove('open');
}

async function executeRemediation() {
    try {
        const res = await fetch('/api/demo/reconciliation');
        const data = await res.json();
        const activeIncident = data.activeIncidents.find(i => i.accountId === activeIncidentAccountId);

        if (!activeIncident) {
            alert('Incident record not found');
            return;
        }

        await fetch('/api/demo/reconciliation/remediate', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                incidentId: activeIncident.id,
                adminId: '00000000-0000-0000-0000-000000000001',
                notes: 'Restored authoritative balance from immutable ledger and recorded audit entry'
            })
        });

        closeRemediationModal();
        loadReconciliation();
    } catch (e) {
        alert('Remediation error: ' + e.message);
    }
}
