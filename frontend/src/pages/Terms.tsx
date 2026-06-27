import termsText from '../../../docs/legal/terms-of-service.md?raw'
import LegalPage from './LegalPage'

export default function Terms() {
  return <LegalPage content={termsText} />
}
