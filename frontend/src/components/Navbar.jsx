import React, { useState, useEffect } from 'react';
import { Link, useResolvedPath, useNavigate } from 'react-router-dom';
import { ShoppingCartIcon, User, LogOut, Settings, Users, BarChart3 } from 'lucide-react';
import ThemeSelector from './ThemeSelector';
import { useAuth } from '../contexts/AuthContext';
import { useTranslation } from 'react-i18next';
import LanguageSelector from './LanguageSelector';

function Navbar() {
  const { pathname } = useResolvedPath();
  const navigate = useNavigate();
  const { isAuthenticated, user, logout, hasRole, hasPermission, api } = useAuth();
  const [showUserMenu, setShowUserMenu] = useState(false);
  const [pendingRequestsCount, setPendingRequestsCount] = useState(0);
  const { t } = useTranslation();

  const isHomePage = pathname === "/";

  // Fetch pending requests count
  useEffect(() => {
    const fetchPendingCount = async () => {
      if (isAuthenticated && (hasRole('Owner') || hasRole('Manager'))) {
        try {
          const response = await api.get('/company/consumers/requests/pending');
          if (response.data.success) {
            setPendingRequestsCount(response.data.data.length);
          }
        } catch (error) {
          console.error('Error fetching pending requests count:', error);
        }
      }
    };

    fetchPendingCount();

    // Auto-refresh every 10 seconds
    const interval = setInterval(fetchPendingCount, 10000);

    return () => clearInterval(interval);
  }, [isAuthenticated, hasRole, api]);

  const handleLogout = async () => {
    await logout();
    navigate('/');
    setShowUserMenu(false);
  };

  const getUserInitials = () => {
    if (!user) return 'U';
    return `${user.firstName?.[0] || ''}${user.lastName?.[0] || ''}`.toUpperCase();
  };

  return (
    <nav className='bg-base-100/80 backdrop-blur-lg border-b border-base-content/10 sticky top-0 z-50' role="navigation" aria-label="Main navigation">
      <div className='max-w-7xl mx-auto'>
        <div className='navbar px-4 min-h-[4rem] justify-between'>
          {/* Logo */}
          <div className='flex-1 lg:flex-none'>
            <Link to='/' className='hover:opacity-80 transition-opacity'>
              <div className='flex items-center gap-2'>
                <ShoppingCartIcon className='size-9 text-primary'/>
                <span className='font-semibold font-mono tracking-widest text-2xl bg-clip-text text-transparent bg-gradient-to-r from-primary to-secondary'>
                  SUDEMAND
                </span>
              </div>
            </Link>
          </div>

          {/* Navigation Links */}
          {isAuthenticated && (
            <div className='hidden md:flex items-center gap-6'>
              <Link 
                to='/dashboard' 
                className='link link-hover font-medium'
              >
                {t('Dashboard')}
              </Link>
              
              {hasPermission('products.read') && (
                <Link 
                  to='/products' 
                  className='link link-hover font-medium'
                >
                  {t('Products')}
                </Link>
              )}
              
              {(hasRole('Owner') || hasRole('Manager')) && (
                <Link 
                  to='/users' 
                  className='link link-hover font-medium'
                >
                  {t('Users')}
                </Link>
              )}
              
              {(hasRole('Owner') || hasRole('Manager')) && (
                <div className="relative">
                  <Link 
                    to='/consumers' 
                    className='link link-hover font-medium'
                  >
                    {t('Consumer Requests')}
                  </Link>
                  {pendingRequestsCount > 0 && (
                    <span className="absolute -top-2 -right-2 bg-red-500 text-white text-xs rounded-full h-5 w-5 flex items-center justify-center font-bold">
                      {pendingRequestsCount}
                    </span>
                  )}
                </div>
              )}
              
              {hasRole('Manager') && (
                <Link 
                  to='/reports' 
                  className='link link-hover font-medium'
                >
                  {t('Reports')}
                </Link>
              )}
              
              {hasRole('Admin') && (
                <Link 
                  to='/admin' 
                  className='link link-hover font-medium'
                >
                  {t('Admin Dashboard')}
                </Link>
              )}
            </div>
          )}

          {/* Right section */}
          <div className='flex items-center gap-4'>
            <LanguageSelector />
            <ThemeSelector />
            
            {isAuthenticated ? (
              <div className="relative">
                <button
                  onClick={() => setShowUserMenu(!showUserMenu)}
                  className="btn btn-ghost btn-circle avatar"
                  aria-label="User menu"
                  aria-expanded={showUserMenu}
                  aria-haspopup="true"
                >
                  <div className="w-8 rounded-full bg-primary text-primary-content flex items-center justify-center font-semibold">
                    {getUserInitials()}
                  </div>
                </button>

                {showUserMenu && (
                  <div 
                    className="absolute right-0 mt-2 w-64 bg-base-100 rounded-box shadow-lg border border-base-content/10 z-50"
                    role="menu"
                    aria-label="User menu"
                  >
                    <div className="p-4 border-b border-base-content/10">
                      <div className="flex items-center gap-3">
                        <div className="w-10 h-10 rounded-full bg-primary text-primary-content flex items-center justify-center font-semibold">
                          {getUserInitials()}
                        </div>
                        <div>
                          <p className="font-semibold">{user?.firstName} {user?.lastName}</p>
                          <p className="text-sm text-base-content/70">{user?.roleName}</p>
                          <p className="text-xs text-base-content/50">{user?.email}</p>
                        </div>
                      </div>
                    </div>

                    <div className="p-2">
                      <Link
                        to="/profile"
                        className="flex items-center gap-3 p-2 rounded-lg hover:bg-base-200 transition-colors"
                        onClick={() => setShowUserMenu(false)}
                      >
                        <User className="w-4 h-4" />
                        {t('Profile')}
                      </Link>

                      <Link
                        to="/settings"
                        className="flex items-center gap-3 p-2 rounded-lg hover:bg-base-200 transition-colors"
                        onClick={() => setShowUserMenu(false)}
                      >
                        <Settings className="w-4 h-4" />
                        {t('Settings')}
                      </Link>

                      {hasRole('Owner') && (
                        <Link
                          to="/users"
                          className="flex items-center gap-3 p-2 rounded-lg hover:bg-base-200 transition-colors"
                          onClick={() => setShowUserMenu(false)}
                        >
                          <Users className="w-4 h-4" />
                          {t('User Management')}
                        </Link>
                      )}

                      {(hasRole('Owner') || hasRole('Manager')) && (
                        <div className="relative">
                          <Link
                            to="/consumers"
                            className="flex items-center gap-3 p-2 rounded-lg hover:bg-base-200 transition-colors"
                            onClick={() => setShowUserMenu(false)}
                          >
                            <Users className="w-4 h-4" />
                            {t('Consumer Requests')}
                            {pendingRequestsCount > 0 && (
                              <span className="ml-auto bg-red-500 text-white text-xs rounded-full h-5 w-5 flex items-center justify-center font-bold">
                                {pendingRequestsCount}
                              </span>
                            )}
                          </Link>
                        </div>
                      )}

                      {hasPermission('dashboard.management') && (
                        <Link
                          to="/analytics"
                          className="flex items-center gap-3 p-2 rounded-lg hover:bg-base-200 transition-colors"
                          onClick={() => setShowUserMenu(false)}
                        >
                        <BarChart3 className="w-4 h-4" />
                        {t('Analytics')}
                        </Link>
                      )}

                      {hasRole('Admin') && (
                        <Link
                          to="/admin"
                          className="flex items-center gap-3 p-2 rounded-lg hover:bg-base-200 transition-colors"
                          onClick={() => setShowUserMenu(false)}
                        >
                        <Settings className="w-4 h-4" />
                        {t('Admin Dashboard')}
                        </Link>
                      )}

                      <div className="divider my-2"></div>

                      <button
                        onClick={handleLogout}
                        className="flex items-center gap-3 p-2 rounded-lg hover:bg-error/10 text-error transition-colors w-full"
                      >
                        <LogOut className="w-4 h-4" />
                        {t('Logout')}
                      </button>
                    </div>
                  </div>
                )}
              </div>
            ) : (
              <div className="flex items-center gap-2">
                <Link to="/login" className="btn btn-ghost">
                  {t('Login')}
                </Link>
                <Link to="/register" className="btn btn-primary">
                  {t('Sign Up')}
                </Link>
              </div>
            )}

            {isHomePage && !isAuthenticated && (
              <div className='indicator'>
                <div className='p-2 rounded-full hover:bg-base-200 transition-colors'>
                  <ShoppingCartIcon className='size-5'/>
                </div>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Click outside to close menu */}
      {showUserMenu && (
        <div 
          className="fixed inset-0 z-40" 
          onClick={() => setShowUserMenu(false)}
        />
      )}
    </nav>
  );
}

export default Navbar;