import React, { useState, useEffect } from 'react';
import { useAuth } from '../contexts/AuthContext';
import { UserPlusIcon, EditIcon, Trash2Icon, UsersIcon, BuildingIcon, AlertTriangleIcon } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';

const UserManagement = () => {
  const { api, user, hasRole, logout } = useAuth();
  const navigate = useNavigate();
  const [users, setUsers] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [showAddModal, setShowAddModal] = useState(false);
  const [editingUser, setEditingUser] = useState(null);
  const [showDeleteCompanyModal, setShowDeleteCompanyModal] = useState(false);
  const { t } = useTranslation();

  // Form data for adding/editing users
  const [formData, setFormData] = useState({
    firstName: '',
    lastName: '',
    email: '',
    phone: '',
    password: '',
    roleName: hasRole('Owner') ? 'Manager' : 'Sales Representative'
  });

  // Fetch users based on role
  const fetchUsers = async () => {
    try {
      setLoading(true);
      const response = await api.get('/users');
      if (response.data.success) {
        setUsers(response.data.data);
      }
    } catch (err) {
      setError(t('Failed to fetch users'));
      console.error('Error fetching users:', err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchUsers();
  }, []);

  const handleSubmit = async (e) => {
    e.preventDefault();
    try {
      if (editingUser) {
        // Update existing user
        const response = await api.put(`/users/${editingUser.id}`, formData);
        if (response.data.success) {
          await fetchUsers();
          setEditingUser(null);
          setShowAddModal(false);
          resetForm();
        }
      } else {
        // Create new user
        const response = await api.post('/users', formData);
        if (response.data.success) {
          await fetchUsers();
          setShowAddModal(false);
          resetForm();
        }
      }
    } catch (err) {
      setError(t('Failed to save user'));
      console.error('Error saving user:', err);
    }
  };

  const handleDelete = async (userId) => {
    if (window.confirm(t('Are you sure you want to delete this user?'))) {
      try {
        const response = await api.delete(`/users/${userId}`);
        if (response.data.success) {
          await fetchUsers();
        }
      } catch (err) {
      setError(t('Failed to delete user'));
        console.error('Error deleting user:', err);
      }
    }
  };

  const handleDeleteCompany = async () => {
    try {
      const response = await api.delete('/users/company');
      if (response.data.success) {
        // Company deactivated successfully, show success message and logout
        alert(t('Company has been deactivated successfully. You will now be logged out.'));
        
        // Clear auth state and redirect to login
        localStorage.removeItem('tokens');
        localStorage.removeItem('user');
        
        // Force logout by redirecting to login page
        window.location.href = '/login';
      }
    } catch (err) {
      setError(t('Failed to deactivate company'));
      console.error('Error deactivating company:', err);
    }
  };

  const resetForm = () => {
    setFormData({
      firstName: '',
      lastName: '',
      email: '',
      phone: '',
      password: '',
      roleName: hasRole('Owner') ? 'Manager' : 'Sales Representative'
    });
  };

  const openEditModal = (user) => {
    setEditingUser(user);
    setFormData({
      firstName: user.first_name,
      lastName: user.last_name,
      email: user.email,
      phone: user.phone || '',
      password: '',
      roleName: user.role_name
    });
    setShowAddModal(true);
  };

  const closeModal = () => {
    setShowAddModal(false);
    setEditingUser(null);
    resetForm();
  };

  // Determine which roles can be managed
  const getAvailableRoles = () => {
    if (hasRole('Owner')) {
      return ['Manager', 'Sales Representative'];
    } else if (hasRole('Manager')) {
      return ['Sales Representative'];
    }
    return [];
  };

  // Filter users based on role permissions
  const getFilteredUsers = () => {
    if (hasRole('Owner')) {
      return users.filter(u => u.role_name !== 'Owner');
    } else if (hasRole('Manager')) {
      return users.filter(u => u.role_name === 'Sales Representative');
    }
    return [];
  };

  const filteredUsers = getFilteredUsers();

  if (loading) {
    return (
      <div className="flex justify-center items-center min-h-screen">
        <div className="loading loading-spinner loading-lg" />
      </div>
    );
  }

  return (
    <div className="container mx-auto px-4 py-8 max-w-6xl">
      <div className="flex justify-between items-center mb-8">
        <div>
          <h1 className="text-3xl font-bold mb-2">{t('User Management')}</h1>
          <p className="text-base-content/70">
            {hasRole('Owner') 
              ? t('Manage Managers and Sales Representatives') 
              : t('Manage Sales Representatives')
            }
          </p>
        </div>
        <div className="flex gap-2">
          <button 
            className="btn btn-primary"
            onClick={() => setShowAddModal(true)}
          >
            <UserPlusIcon className="size-5 mr-2" />
            {t('Add User')}
          </button>
                  {hasRole('Owner') && (
                    <button 
                      className="btn btn-warning btn-outline"
                      onClick={() => setShowDeleteCompanyModal(true)}
                    >
                      <BuildingIcon className="size-5 mr-2" />
                      {t('Deactivate Company')}
                    </button>
                  )}
        </div>
      </div>

      {error && (
        <div className="alert alert-error mb-6">
          {error}
        </div>
      )}

      {/* Users Table */}
      <div className="card bg-base-100 shadow-lg">
        <div className="card-body">
          <div className="overflow-x-auto">
            <table className="table table-zebra w-full">
              <thead>
                <tr>
                  <th>{t('Name')}</th>
                  <th>{t('Email')}</th>
                  <th>{t('Phone')}</th>
                  <th>{t('Role')}</th>
                  <th>{t('Company')}</th>
                  <th>{t('Actions')}</th>
                </tr>
              </thead>
              <tbody>
                {filteredUsers.map((user) => (
                  <tr key={user.id}>
                    <td>
                      <div className="flex items-center gap-3">
                        <div className="avatar placeholder">
                          <div className="bg-primary text-primary-content rounded-full w-8">
                            <span className="text-xs">
                              {user.first_name[0]}{user.last_name[0]}
                            </span>
                          </div>
                        </div>
                        <div>
                          <div className="font-semibold">{user.first_name} {user.last_name}</div>
                        </div>
                      </div>
                    </td>
                    <td>{user.email}</td>
                    <td>{user.phone || '-'}</td>
                    <td>
                      <span className={`badge ${
                        user.role_name === 'Manager' ? 'badge-warning' : 'badge-info'
                      }`}>
                        {t(user.role_name)}
                      </span>
                    </td>
                    <td>{user.company_name || '-'}</td>
                    <td>
                      <div className="flex gap-2">
                        <button
                          className="btn btn-sm btn-info btn-outline"
                          onClick={() => openEditModal(user)}
                        >
                          <EditIcon className="size-4" />
                        </button>
                        <button
                          className="btn btn-sm btn-error btn-outline"
                          onClick={() => handleDelete(user.id)}
                        >
                          <Trash2Icon className="size-4" />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {filteredUsers.length === 0 && (
            <div className="text-center py-8">
              <UsersIcon className="size-12 mx-auto mb-4 text-base-content/30" />
              <h3 className="text-lg font-semibold mb-2">{t('No users found')}</h3>
              <p className="text-base-content/70 mb-4">
                {hasRole('Owner') 
                  ? t('Start by adding a Manager or Sales Representative') 
                  : t('Start by adding a Sales Representative')
                }
              </p>
            </div>
          )}
        </div>
      </div>

      {/* Add/Edit User Modal */}
      {showAddModal && (
        <div 
          className="modal modal-open"
          role="dialog"
          aria-labelledby="user-modal-title"
          aria-modal="true"
        >
          <div className="modal-box max-w-md">
            <h3 id="user-modal-title" className="font-bold text-lg mb-4">
              {editingUser ? t('Edit User') : t('Add New User')}
            </h3>
            
            <form onSubmit={handleSubmit} className="space-y-4">
              <div className="grid grid-cols-2 gap-4">
                <div className="form-control">
                  <label htmlFor="user-first-name" className="label">
                    <span className="label-text">{t('First Name')}</span>
                  </label>
                  <input
                    id="user-first-name"
                    type="text"
                    className="input input-bordered"
                    value={formData.firstName}
                    onChange={(e) => setFormData({...formData, firstName: e.target.value})}
                    required
                    aria-required="true"
                    autoComplete="given-name"
                  />
                </div>
                <div className="form-control">
                  <label htmlFor="user-last-name" className="label">
                    <span className="label-text">{t('Last Name')}</span>
                  </label>
                  <input
                    id="user-last-name"
                    type="text"
                    className="input input-bordered"
                    value={formData.lastName}
                    onChange={(e) => setFormData({...formData, lastName: e.target.value})}
                    required
                    aria-required="true"
                    autoComplete="family-name"
                  />
                </div>
              </div>

              <div className="form-control">
                <label htmlFor="user-email" className="label">
                  <span className="label-text">{t('Email')}</span>
                </label>
                <input
                  id="user-email"
                  type="email"
                  className="input input-bordered"
                  value={formData.email}
                  onChange={(e) => setFormData({...formData, email: e.target.value})}
                  required
                  aria-required="true"
                  autoComplete="email"
                />
              </div>

              <div className="form-control">
                <label htmlFor="user-phone" className="label">
                  <span className="label-text">{t('Phone (Optional)')}</span>
                </label>
                <input
                  id="user-phone"
                  type="tel"
                  className="input input-bordered"
                  value={formData.phone}
                  onChange={(e) => setFormData({...formData, phone: e.target.value})}
                  autoComplete="tel"
                />
              </div>

              <div className="form-control">
                <label htmlFor="user-role" className="label">
                  <span className="label-text">{t('Role')}</span>
                </label>
                <select
                  id="user-role"
                  className="select select-bordered"
                  value={formData.roleName}
                  onChange={(e) => setFormData({...formData, roleName: e.target.value})}
                  required
                  aria-required="true"
                >
                  {getAvailableRoles().map(role => (
                    <option key={role} value={role}>{t(role)}</option>
                  ))}
                </select>
              </div>

              <div className="form-control">
                <label htmlFor="user-password" className="label">
                  <span className="label-text">
                    {editingUser ? t('Password (leave blank to keep current)') : t('Password')}
                  </span>
                </label>
                <input
                  id="user-password"
                  type="password"
                  className="input input-bordered"
                  value={formData.password}
                  onChange={(e) => setFormData({...formData, password: e.target.value})}
                  required={!editingUser}
                  aria-required={!editingUser}
                  autoComplete={editingUser ? "current-password" : "new-password"}
                />
              </div>

              <div className="modal-action">
                <button 
                  type="button" 
                  className="btn btn-ghost" 
                  onClick={closeModal}
                  aria-label={t('Cancel and close dialog')}
                >
                  {t('Cancel')}
                </button>
                <button 
                  type="submit" 
                  className="btn btn-primary"
                  aria-label={editingUser ? t('Update user') : t('Create new user')}
                >
                  {editingUser ? t('Update User') : t('Create User')}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Deactivate Company Confirmation Modal */}
      {showDeleteCompanyModal && (
        <div className="modal modal-open">
          <div className="modal-box max-w-md">
            <div className="flex items-center gap-3 mb-4">
              <AlertTriangleIcon className="size-8 text-warning" />
              <h3 className="font-bold text-lg text-warning">{t('Deactivate Company')}</h3>
            </div>
            
            <div className="space-y-4">
              <div className="alert alert-warning">
                <AlertTriangleIcon className="size-6" />
                <div>
                  <h4 className="font-bold">{t('Warning: This will deactivate your company!')}</h4>
                  <p className="text-sm">
                    {t('This will deactivate your company and prevent all users from logging in. However, all data will be preserved:')}
                  </p>
                  <ul className="text-sm mt-2 list-disc list-inside">
                    <li>{t('All user accounts will be deactivated')}</li>
                    <li>{t('All products will be archived')}</li>
                    <li>{t('All company data will be preserved')}</li>
                    <li>{t('All audit logs will be maintained')}</li>
                  </ul>
                  <p className="text-sm mt-2 font-semibold">
                    {t('Users will not be able to log in until the company is reactivated.')}
                  </p>
                </div>
              </div>

              <div className="form-control">
                <label className="label">
                  <span className="label-text">{t('Type "DEACTIVATE" to confirm')}</span>
                </label>
                <input
                  type="text"
                  className="input input-bordered"
                  placeholder={t('Type DEACTIVATE to confirm')}
                  id="deleteConfirmation"
                />
              </div>
            </div>

            <div className="modal-action">
              <button 
                className="btn btn-ghost" 
                onClick={() => setShowDeleteCompanyModal(false)}
              >
                {t('Cancel')}
              </button>
              <button 
                className="btn btn-warning"
                onClick={() => {
                  const confirmation = document.getElementById('deleteConfirmation').value;
                  if (confirmation === 'DEACTIVATE') {
                    handleDeleteCompany();
                    setShowDeleteCompanyModal(false);
                  } else {
                    alert(t('Please type "DEACTIVATE" to confirm'));
                  }
                }}
              >
                {t('Deactivate Company')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default UserManagement;
