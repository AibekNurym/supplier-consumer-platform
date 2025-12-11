import { render, screen } from '@testing-library/react';
import { axe, toHaveNoViolations } from 'jest-axe';
import App from './App';
import { AuthProvider } from './contexts/AuthContext';
import { ThemeProvider } from './store/useThemeStore';

expect.extend(toHaveNoViolations);

// Mock the auth context
jest.mock('./contexts/AuthContext', () => ({
  useAuth: () => ({
    isAuthenticated: false,
    loading: false,
  }),
  AuthProvider: ({ children }) => children,
}));

// Mock react-router-dom
jest.mock('react-router-dom', () => ({
  BrowserRouter: ({ children }) => <div>{children}</div>,
  Routes: ({ children }) => <div>{children}</div>,
  Route: () => null,
  Navigate: () => null,
  Link: ({ children }) => <a>{children}</a>,
}));

describe('App Accessibility', () => {
  it('should not have any accessibility violations', async () => {
    const { container } = render(<App />);
    const results = await axe(container);
    expect(results).toHaveNoViolations();
  });

  it('should have skip navigation link', () => {
    render(<App />);
    const skipLink = screen.queryByText(/skip to main content/i);
    expect(skipLink).toBeInTheDocument();
  });

  it('should have ARIA live region for dynamic updates', () => {
    render(<App />);
    const liveRegion = document.getElementById('aria-live-region');
    expect(liveRegion).toBeInTheDocument();
    expect(liveRegion).toHaveAttribute('aria-live', 'polite');
    expect(liveRegion).toHaveAttribute('aria-atomic', 'true');
  });
});









