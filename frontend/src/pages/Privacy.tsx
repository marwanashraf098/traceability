import policyText from '../../../docs/legal/privacy-policy.md?raw'
import LegalPage from './LegalPage'

export default function Privacy() {
  return <LegalPage content={policyText} />
}
