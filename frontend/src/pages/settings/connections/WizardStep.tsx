import { ReactNode } from 'react'
import { Progress } from '../../../components/ui'

// Shared pieces of the Connections step wizards (the custom-app SetupWizard and the "Find your store"
// guide): the title + "Step x of y" + progress header, and one step's title/body block.

export function WizardHeader({ title, stepLabel, step, total }: {
  title: string
  stepLabel: string
  step: number
  total: number
}) {
  return (
    <>
      <h3 className="text-h4 text-primary">{title}</h3>
      <div className="space-y-2">
        <span className="text-small text-muted font-medium">{stepLabel}</span>
        <Progress value={(step / total) * 100} />
      </div>
    </>
  )
}

export function StepBody({ title, body, children }: { title: string; body: ReactNode; children?: ReactNode }) {
  return (
    <div className="space-y-3">
      <div>
        <h4 className="text-body-lg font-semibold text-primary mb-1">{title}</h4>
        <p className="text-body text-muted">{body}</p>
      </div>
      {children}
    </div>
  )
}
