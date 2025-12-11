import Navbar from './components/Navbar';
import HomePage from './pages/HomePage';
import ProductPage from './pages/ProductPage';
import Dashboard from './pages/Dashboard';
import UserManagement from './pages/UserManagement';
import ConsumerRequests from './pages/ConsumerRequests';
import AuthModal from './components/AuthModal';
import ProtectedRoute, { OwnerRoute, ManagerRoute, SalesRoute, AdminRoute } from './components/ProtectedRoute';
import AdminDashboard from './pages/AdminDashboard';

import { Routes, Route, Navigate } from 'react-router-dom';
import { useThemeStore } from './store/useThemeStore';
import { useAuth } from './contexts/AuthContext';
import { Toaster } from 'react-hot-toast';
import { useTranslation } from 'react-i18next';

function App() {
  const { theme } = useThemeStore();
  const { isAuthenticated, loading } = useAuth();
  const { t } = useTranslation();

  if (loading) {
    return (
      <div className="min-h-screen bg-base-200 flex items-center justify-center" data-theme={theme}>
        <div className="loading loading-spinner loading-lg"></div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-base-200 transition-colors duration-300" data-theme={theme}>
      {/* Skip Navigation Link */}
      <a 
        href="#main-content" 
        className="sr-only focus:not-sr-only focus:absolute focus:top-4 focus:left-4 focus:z-[100] focus:px-4 focus:py-2 focus:bg-primary focus:text-primary-content focus:rounded-lg focus:shadow-lg"
      >
        {t('Skip to main content')}
      </a>
      
      {/* ARIA Live Region for Dynamic Updates */}
      <div 
        aria-live="polite" 
        aria-atomic="true" 
        className="sr-only"
        id="aria-live-region"
      />
      
      <Navbar />

      <Routes>
        {/* Protected routes */}
        <Route 
          path="/" 
          element={
            <ProtectedRoute>
              <HomePage />
            </ProtectedRoute>
          } 
        />
        <Route 
          path="/products" 
          element={
            <ProtectedRoute>
              <HomePage />
            </ProtectedRoute>
          } 
        />
        <Route 
          path="/product/:id" 
          element={
            <ProtectedRoute>
              <ProductPage />
            </ProtectedRoute>
          } 
        />
        
        {/* Auth routes */}
        <Route 
          path="/login" 
          element={!isAuthenticated ? <AuthModal /> : <Navigate to="/dashboard" replace />} 
        />
        <Route 
          path="/register" 
          element={!isAuthenticated ? <AuthModal /> : <Navigate to="/dashboard" replace />} 
        />

        {/* Protected routes */}
        <Route 
          path="/dashboard" 
          element={
            <ProtectedRoute>
              <Dashboard />
            </ProtectedRoute>
          } 
        />

        {/* Role-specific routes */}
        <Route 
          path="/users" 
          element={
            <ProtectedRoute>
              <UserManagement />
            </ProtectedRoute>
          } 
        />

        <Route 
          path="/consumers" 
          element={
            <ProtectedRoute>
              <ConsumerRequests />
            </ProtectedRoute>
          } 
        />

        {/* Admin routes */}
        <Route 
          path="/admin" 
          element={
            <AdminRoute>
              <AdminDashboard />
            </AdminRoute>
          } 
        />

        {/* Catch all route */}
        <Route path="*" element={<Navigate to="/login" replace />} />
      </Routes>

      <Toaster />
    </div>
  );
}

export default App;
