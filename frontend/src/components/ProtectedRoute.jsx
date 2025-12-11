import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';
import { useTranslation } from 'react-i18next';

const ProtectedRoute = ({ children, requiredRoles = [], requiredPermissions = [] }) => {
  const { isAuthenticated, user, loading, hasAnyRole, hasPermission } = useAuth();
  const location = useLocation();
  const { t } = useTranslation();

  // Show loading spinner while checking authentication
  if (loading) {
    return (
      <div className="min-h-screen bg-base-200 flex items-center justify-center">
        <div className="loading loading-spinner loading-lg"></div>
      </div>
    );
  }

  // Redirect to login if not authenticated
  if (!isAuthenticated) {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }

  // Check role requirements
  if (requiredRoles.length > 0 && !hasAnyRole(requiredRoles)) {
    return (
      <div className="min-h-screen bg-base-200 flex items-center justify-center">
        <div className="card w-96 bg-base-100 shadow-xl">
          <div className="card-body text-center">
            <h2 className="card-title text-error justify-center">{t('Access Denied')}</h2>
            <p className="text-base-content/70">
              {t("You don't have the required role to access this page.")}
            </p>
            <p className="text-sm text-base-content/50 mt-2">
              {t('Required roles: {{roles}}', { roles: requiredRoles.join(', ') })}
            </p>
            <div className="card-actions justify-center mt-4">
              <button 
                className="btn btn-primary"
                onClick={() => window.history.back()}
              >
                {t('Go Back')}
              </button>
            </div>
          </div>
        </div>
      </div>
    );
  }

  // Check permission requirements
  if (requiredPermissions.length > 0) {
    const hasAllPermissions = requiredPermissions.every(permission => 
      hasPermission(permission)
    );

    if (!hasAllPermissions) {
      return (
        <div className="min-h-screen bg-base-200 flex items-center justify-center">
          <div className="card w-96 bg-base-100 shadow-xl">
            <div className="card-body text-center">
              <h2 className="card-title text-error justify-center">{t('Access Denied')}</h2>
              <p className="text-base-content/70">
                {t("You don't have the required permissions to access this page.")}
              </p>
              <p className="text-sm text-base-content/50 mt-2">
                {t('Required permissions: {{permissions}}', { permissions: requiredPermissions.join(', ') })}
              </p>
              <div className="card-actions justify-center mt-4">
                <button 
                  className="btn btn-primary"
                  onClick={() => window.history.back()}
                >
                  {t('Go Back')}
                </button>
              </div>
            </div>
          </div>
        </div>
      );
    }
  }

  // User is authenticated and has required roles/permissions
  return children;
};

// Convenience components for common role-based routes
export const OwnerRoute = ({ children }) => (
  <ProtectedRoute requiredRoles={['Owner']}>
    {children}
  </ProtectedRoute>
);

export const ManagerRoute = ({ children }) => (
  <ProtectedRoute requiredRoles={['Owner', 'Manager']}>
    {children}
  </ProtectedRoute>
);

export const SalesRoute = ({ children }) => (
  <ProtectedRoute requiredRoles={['Owner', 'Manager', 'Sales Representative']}>
    {children}
  </ProtectedRoute>
);

export const AdminRoute = ({ children }) => (
  <ProtectedRoute requiredRoles={['Admin']}>
    {children}
  </ProtectedRoute>
);

// Permission-based route component
export const PermissionRoute = ({ children, permissions }) => (
  <ProtectedRoute requiredPermissions={permissions}>
    {children}
  </ProtectedRoute>
);

export default ProtectedRoute;

