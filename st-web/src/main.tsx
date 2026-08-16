import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import App from './App'
import { ToastProvider } from './components/ui/Toast'
import { ConfirmProvider } from './components/ui/ConfirmDialog'
import { PromptProvider } from './components/ui/PromptDialog'
import { OperationProgressProvider } from './components/ui/OperationProgress'
import './store/theme'
import './index.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <ToastProvider>
        <ConfirmProvider>
          <PromptProvider>
            <OperationProgressProvider>
              <App />
            </OperationProgressProvider>
          </PromptProvider>
        </ConfirmProvider>
      </ToastProvider>
    </BrowserRouter>
  </StrictMode>,
)
