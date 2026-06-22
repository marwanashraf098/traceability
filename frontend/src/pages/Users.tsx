import { useState, useEffect, FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { listUsers, createUser, updateUser, deactivateUser, getRoleFromToken, User } from '../api'
import { Modal } from '../components/ui'

// ── Role badge ────────────────────────────────────────────────────────────────

const ROLE_STYLE: Record<string, string> = {
  owner:   'bg-brand/15 text-brand border-brand/25',
  manager: 'bg-accent/10 text-accent border-accent/25',
  worker:  'bg-elevated text-muted border-line',
}

function RoleBadge({ role }: { role: string }) {
  const { t } = useTranslation()
  return (
    <span className={`inline-flex items-center px-2 py-0.5 rounded text-caption font-medium border ${ROLE_STYLE[role] ?? ROLE_STYLE.worker}`}>
      {t(`users.roles.${role}`, role)}
    </span>
  )
}

// ── Create user modal ─────────────────────────────────────────────────────────

function CreateUserModal({
  currentRole,
  onCreated,
  onClose,
}: {
  currentRole: 'owner' | 'manager' | 'worker' | null
  onCreated: () => void
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [name,     setName]     = useState('')
  const [email,    setEmail]    = useState('')
  const [role,     setRole]     = useState('worker')
  const [password, setPassword] = useState('')
  const [pin,      setPin]      = useState('')
  const [loading,  setLoading]  = useState(false)
  const [error,    setError]    = useState('')

  const isWorkerRole = role === 'worker'

  const roleOptions = [
    { value: 'worker',  label: t('users.roles.worker') },
    { value: 'manager', label: t('users.roles.manager') },
    ...(currentRole === 'owner' ? [{ value: 'owner', label: t('users.roles.owner') }] : []),
  ]

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')

    if (isWorkerRole && pin.length !== 4) {
      setError(t('users.create.errors.generic'))
      return
    }
    if (!isWorkerRole && password.length < 8) {
      setError(t('users.create.errors.generic'))
      return
    }

    setLoading(true)
    try {
      await createUser({
        name: name.trim(),
        email: email.trim() || undefined,
        role,
        password: !isWorkerRole ? password : undefined,
        pin:      isWorkerRole  ? pin      : undefined,
      })
      onCreated()
      onClose()
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : ''
      if (msg.includes('403')) setError(t('users.create.errors.forbidden'))
      else if (msg.includes('409')) setError(t('users.create.errors.emailTaken'))
      else setError(t('users.create.errors.generic'))
    } finally {
      setLoading(false)
    }
  }

  return (
    <Modal title={t('users.create.title')} onClose={onClose}>
      {error && (
        <div role="alert" className="text-small text-danger bg-danger/10 border border-danger/25 rounded px-3 py-2 mb-4">
          {error}
        </div>
      )}

      <form onSubmit={handleSubmit} className="space-y-4">
        <div>
          <label className="block text-small text-muted mb-1.5" htmlFor="createName">
            {t('users.create.name')}
          </label>
          <input
            id="createName"
            type="text"
            required
            value={name}
            onChange={e => setName(e.target.value)}
            className="input"
            autoFocus
          />
        </div>

        <div>
          <label className="block text-small text-muted mb-1.5" htmlFor="createEmail">
            {t('users.create.email')}
          </label>
          <input
            id="createEmail"
            type="email"
            value={email}
            onChange={e => setEmail(e.target.value)}
            className="input"
          />
        </div>

        <div>
          <label className="block text-small text-muted mb-1.5" htmlFor="createRole">
            {t('users.create.role')}
          </label>
          <select
            id="createRole"
            value={role}
            onChange={e => setRole(e.target.value)}
            className="input"
          >
            {roleOptions.map(o => (
              <option key={o.value} value={o.value}>{o.label}</option>
            ))}
          </select>
          <p className="text-caption text-muted mt-1">{t('users.create.roleHint')}</p>
          {currentRole !== 'owner' && (
            <p className="text-caption text-muted mt-0.5">{t('users.create.ownerRoleHint')}</p>
          )}
        </div>

        {isWorkerRole ? (
          <div>
            <label className="block text-small text-muted mb-1.5" htmlFor="createPin">
              {t('users.create.pin')}
            </label>
            <input
              id="createPin"
              type="password"
              inputMode="numeric"
              maxLength={4}
              minLength={4}
              pattern="[0-9]{4}"
              value={pin}
              onChange={e => setPin(e.target.value.replace(/\D/g, '').slice(0, 4))}
              className="input w-28"
              dir="ltr"
            />
            <p className="text-caption text-muted mt-1">{t('users.create.pinHint')}</p>
          </div>
        ) : (
          <div>
            <label className="block text-small text-muted mb-1.5" htmlFor="createPassword">
              {t('users.create.password')}
            </label>
            <input
              id="createPassword"
              type="password"
              value={password}
              onChange={e => setPassword(e.target.value)}
              className="input"
              autoComplete="new-password"
              minLength={8}
            />
            <p className="text-caption text-muted mt-1">{t('users.create.passwordHint')}</p>
          </div>
        )}

        <div className="flex gap-2 pt-1">
          <button
            type="submit"
            disabled={loading || !name.trim()}
            className="btn btn-brand"
          >
            {loading ? t('users.create.creating') : t('users.create.submit')}
          </button>
          <button type="button" onClick={onClose} className="btn btn-ghost">
            {t('common.cancel')}
          </button>
        </div>
      </form>
    </Modal>
  )
}

// ── Edit user modal ───────────────────────────────────────────────────────────

function EditUserModal({
  user,
  currentRole,
  onSaved,
  onClose,
}: {
  user: User
  currentRole: 'owner' | 'manager' | 'worker' | null
  onSaved: () => void
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [name,    setName]    = useState(user.name)
  const [role,    setRole]    = useState(user.role)
  const [loading, setLoading] = useState(false)
  const [error,   setError]   = useState('')

  const roleOptions = [
    { value: 'worker',  label: t('users.roles.worker') },
    { value: 'manager', label: t('users.roles.manager') },
    ...(currentRole === 'owner' ? [{ value: 'owner', label: t('users.roles.owner') }] : []),
  ]

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      await updateUser(user.id, { name: name.trim(), role })
      onSaved()
      onClose()
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : ''
      if (msg.includes('403')) setError(t('users.edit.errors.forbidden'))
      else setError(t('users.edit.errors.generic'))
    } finally {
      setLoading(false)
    }
  }

  return (
    <Modal title={t('users.edit.title')} onClose={onClose}>
      {error && (
        <div role="alert" className="text-small text-danger bg-danger/10 border border-danger/25 rounded px-3 py-2 mb-4">
          {error}
        </div>
      )}

      <form onSubmit={handleSubmit} className="space-y-4">
        <div>
          <label className="block text-small text-muted mb-1.5" htmlFor="editName">
            {t('users.create.name')}
          </label>
          <input
            id="editName"
            type="text"
            required
            value={name}
            onChange={e => setName(e.target.value)}
            className="input"
            autoFocus
          />
        </div>

        {/* Only show role selector if current user can change it (Manager can't touch Owner) */}
        {!(currentRole === 'manager' && user.role === 'owner') && (
          <div>
            <label className="block text-small text-muted mb-1.5" htmlFor="editRole">
              {t('users.create.role')}
            </label>
            <select
              id="editRole"
              value={role}
              onChange={e => setRole(e.target.value as User['role'])}
              className="input"
            >
              {roleOptions.map(o => (
                <option key={o.value} value={o.value}>{o.label}</option>
              ))}
            </select>
          </div>
        )}

        <div className="flex gap-2 pt-1">
          <button
            type="submit"
            disabled={loading || !name.trim()}
            className="btn btn-brand"
          >
            {loading ? t('users.edit.saving') : t('users.edit.save')}
          </button>
          <button type="button" onClick={onClose} className="btn btn-ghost">
            {t('common.cancel')}
          </button>
        </div>
      </form>
    </Modal>
  )
}

// ── Deactivate confirmation modal ─────────────────────────────────────────────

function DeactivateModal({
  user,
  onDeactivated,
  onClose,
}: {
  user: User
  onDeactivated: () => void
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [loading, setLoading] = useState(false)
  const [error,   setError]   = useState('')

  async function handleConfirm() {
    setError('')
    setLoading(true)
    try {
      await deactivateUser(user.id)
      onDeactivated()
      onClose()
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : ''
      if (msg.includes('403')) setError(t('users.deactivate.errors.forbidden'))
      else setError(t('users.deactivate.errors.generic'))
    } finally {
      setLoading(false)
    }
  }

  const body = t('users.deactivate.body', { name: user.name })

  return (
    <Modal title={t('users.deactivate.title')} onClose={onClose}>
      {error && (
        <div role="alert" className="text-small text-danger bg-danger/10 border border-danger/25 rounded px-3 py-2 mb-4">
          {error}
        </div>
      )}

      <p className="text-small text-muted mb-5">{body}</p>

      <div className="flex gap-2">
        <button
          onClick={handleConfirm}
          disabled={loading}
          className="btn btn-danger"
        >
          {loading ? t('common.loading') : t('users.deactivate.confirm')}
        </button>
        <button onClick={onClose} className="btn btn-ghost">
          {t('common.cancel')}
        </button>
      </div>
    </Modal>
  )
}

// ── Users page ────────────────────────────────────────────────────────────────

export default function Users() {
  const { t } = useTranslation()
  const currentRole = getRoleFromToken()

  const [users,    setUsers]    = useState<User[]>([])
  const [loading,  setLoading]  = useState(true)
  const [error,    setError]    = useState('')

  const [showCreate,    setShowCreate]    = useState(false)
  const [editTarget,    setEditTarget]    = useState<User | null>(null)
  const [deactivTarget, setDeactivTarget] = useState<User | null>(null)

  async function load() {
    setLoading(true)
    setError('')
    try {
      setUsers(await listUsers())
    } catch {
      setError(t('common.error'))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { load() }, [])

  function canEdit(u: User) {
    if (currentRole === 'owner') return true
    if (currentRole === 'manager' && u.role !== 'owner') return true
    return false
  }

  return (
    <div className="max-w-4xl mx-auto space-y-6">
      <div className="flex items-center justify-between gap-4">
        <div>
          <h1 className="text-h1 text-primary">{t('users.title')}</h1>
          <p className="text-small text-muted mt-1">{t('users.subtitle')}</p>
        </div>
        <button onClick={() => setShowCreate(true)} className="btn btn-brand flex-shrink-0">
          {t('users.addUser')}
        </button>
      </div>

      {error && (
        <div role="alert" className="text-small text-danger bg-danger/10 border border-danger/25 rounded px-3 py-2">
          {error}
        </div>
      )}

      {loading ? (
        <div className="flex items-center justify-center py-16">
          <svg className="animate-spin w-6 h-6 text-brand" fill="none" viewBox="0 0 24 24">
            <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"/>
            <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"/>
          </svg>
        </div>
      ) : users.length === 0 ? (
        <div className="card p-10 text-center text-muted text-small">{t('users.noUsers')}</div>
      ) : (
        <div className="card overflow-hidden">
          <table className="w-full">
            <thead>
              <tr>
                <th className="tbl-header text-start">{t('users.col.name')}</th>
                <th className="tbl-header text-start">{t('users.col.email')}</th>
                <th className="tbl-header text-start">{t('users.col.role')}</th>
                <th className="tbl-header text-start">{t('users.col.status')}</th>
                <th className="tbl-header" />
              </tr>
            </thead>
            <tbody>
              {users.map(u => (
                <tr key={u.id} className="tbl-row">
                  <td className="tbl-cell text-primary font-medium">{u.name}</td>
                  <td className="tbl-cell text-muted">{u.email ?? t('common.na')}</td>
                  <td className="tbl-cell"><RoleBadge role={u.role} /></td>
                  <td className="tbl-cell">
                    <span className={`text-small ${u.active ? 'text-success' : 'text-muted'}`}>
                      {u.active ? t('users.status.active') : t('users.status.inactive')}
                    </span>
                  </td>
                  <td className="tbl-cell">
                    <div className="flex items-center justify-end gap-2">
                      {canEdit(u) && (
                        <button
                          onClick={() => setEditTarget(u)}
                          className="btn btn-ghost text-small py-1 px-2"
                        >
                          {t('users.edit.btn')}
                        </button>
                      )}
                      {canEdit(u) && u.active && (
                        <button
                          onClick={() => setDeactivTarget(u)}
                          className="btn btn-danger text-small py-1 px-2"
                        >
                          {t('users.deactivate.confirm')}
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {showCreate && (
        <CreateUserModal
          currentRole={currentRole}
          onCreated={load}
          onClose={() => setShowCreate(false)}
        />
      )}

      {editTarget && (
        <EditUserModal
          user={editTarget}
          currentRole={currentRole}
          onSaved={load}
          onClose={() => setEditTarget(null)}
        />
      )}

      {deactivTarget && (
        <DeactivateModal
          user={deactivTarget}
          onDeactivated={load}
          onClose={() => setDeactivTarget(null)}
        />
      )}
    </div>
  )
}
