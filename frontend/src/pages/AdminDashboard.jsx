import { useState, useEffect } from 'react';
import { useAuth } from '../contexts/AuthContext';
import toast from 'react-hot-toast';
import { useTranslation } from 'react-i18next';

const AdminDashboard = () => {
  const { api } = useAuth();
  const { t } = useTranslation();
  const [companies, setCompanies] = useState([]);
  const [loading, setLoading] = useState(true);
  const [rejectModal, setRejectModal] = useState({ open: false, companyId: null, rejectionMessage: '' });
  const [profileModal, setProfileModal] = useState(false);
  const [profileData, setProfileData] = useState({
    email: '',
    firstName: '',
    lastName: '',
    phone: '',
  });
  const [passwordModal, setPasswordModal] = useState(false);
  const [passwordData, setPasswordData] = useState({
    currentPassword: '',
    newPassword: '',
    confirmPassword: '',
  });

  useEffect(() => {
    fetchCompanies();
  }, []);

  const fetchCompanies = async () => {
    try {
      setLoading(true);
      const response = await api.get('/admin/companies');
      if (response.data.success) {
        setCompanies(response.data.data);
      }
    } catch (error) {
      console.error('Error fetching companies:', error);
      toast.error(t('Failed to fetch companies'));
    } finally {
      setLoading(false);
    }
  };

  const handleApprove = async (companyId) => {
    try {
      const response = await api.put(`/admin/companies/${companyId}/approve`);
      if (response.data.success) {
        toast.success(t('Company approved successfully'));
        fetchCompanies();
      }
    } catch (error) {
      console.error('Error approving company:', error);
      toast.error(error.response?.data?.message || t('Failed to approve company'));
    }
  };

  const handleReject = async () => {
    if (!rejectModal.rejectionMessage.trim()) {
      toast.error(t('Please provide a rejection reason'));
      return;
    }

    try {
      const response = await api.put(`/admin/companies/${rejectModal.companyId}/reject`, {
        rejectionMessage: rejectModal.rejectionMessage,
      });
      if (response.data.success) {
        toast.success(t('Company rejected successfully'));
        setRejectModal({ open: false, companyId: null, rejectionMessage: '' });
        fetchCompanies();
      }
    } catch (error) {
      console.error('Error rejecting company:', error);
      toast.error(error.response?.data?.message || t('Failed to reject company'));
    }
  };

  const handleDelete = async (companyId) => {
    if (!confirm(t('Are you sure you want to delete this rejected company and all owner credentials?'))) {
      return;
    }

    try {
      const response = await api.delete(`/admin/companies/${companyId}`);
      if (response.data.success) {
        toast.success(t('Company and owner credentials deleted successfully'));
        fetchCompanies();
      }
    } catch (error) {
      console.error('Error deleting company:', error);
      toast.error(error.response?.data?.message || t('Failed to delete company'));
    }
  };

  const fetchProfile = async () => {
    try {
      const response = await api.get('/admin/profile');
      if (response.data.success) {
        const user = response.data.data.user;
        setProfileData({
          email: user.email,
          firstName: user.firstName,
          lastName: user.lastName,
          phone: user.phone || '',
        });
        setProfileModal(true);
      }
    } catch (error) {
      console.error('Error fetching profile:', error);
      toast.error(t('Failed to fetch profile'));
    }
  };

  const handleUpdateProfile = async () => {
    try {
      const response = await api.put('/admin/profile', profileData);
      if (response.data.success) {
        toast.success(t('Profile updated successfully'));
        setProfileModal(false);
      }
    } catch (error) {
      console.error('Error updating profile:', error);
      toast.error(error.response?.data?.message || t('Failed to update profile'));
    }
  };

  const handleChangePassword = async () => {
    if (passwordData.newPassword !== passwordData.confirmPassword) {
      toast.error(t('Passwords do not match'));
      return;
    }

    try {
      const response = await api.put('/admin/change-password', {
        currentPassword: passwordData.currentPassword,
        newPassword: passwordData.newPassword,
      });
      if (response.data.success) {
        toast.success(t('Password changed successfully'));
        setPasswordModal(false);
        setPasswordData({
          currentPassword: '',
          newPassword: '',
          confirmPassword: '',
        });
      }
    } catch (error) {
      console.error('Error changing password:', error);
      toast.error(error.response?.data?.message || t('Failed to change password'));
    }
  };

  const downloadDocument = async (doc) => {
    try {
      console.log('Download document called with:', doc);
      const fileName = doc.originalname || doc.filename;
      
      // Check if this is an old format document (no id, has path)
      if (doc.isOldFormat || (!doc.id && doc.path)) {
        toast.error(t('This document was uploaded before database storage was implemented. The file may not be available. Please ask the company to re-register with their documents.'));
        return;
      }
      
      // Check if document has id and downloadUrl
      if (!doc.id) {
        toast.error(t('Document ID is missing. This may be a legacy document. Please ask the company to re-register.'));
        return;
      }
      
      if (!doc.downloadUrl) {
        toast.error(t('Document download URL is missing.'));
        return;
      }
      
      // New format: download from database
      const downloadUrl = doc.downloadUrl.replace('/api', '') || `/admin/documents/${doc.id}`;
      
      console.log('Downloading document from:', downloadUrl);
      console.log('Document ID:', doc.id);
      
      // Fetch the file from the database endpoint using axios with blob response type
      const response = await api.get(downloadUrl, {
        responseType: 'blob', // Important for binary data
      });
      
      // Get content type from headers or document metadata
      const contentType = response.headers['content-type'] || 
                         doc.mimetype || 
                         'application/octet-stream';
      
      // Create a blob from the response data
      const blob = new Blob([response.data], { type: contentType });
      const url = window.URL.createObjectURL(blob);
      
      // Create a temporary anchor element and trigger download
      const link = window.document.createElement('a');
      link.href = url;
      link.download = fileName;
      link.style.display = 'none';
      
      // Append to body, click, and remove
      window.document.body.appendChild(link);
      link.click();
      
      // Clean up after a short delay
      setTimeout(() => {
        if (window.document.body.contains(link)) {
          window.document.body.removeChild(link);
        }
        window.URL.revokeObjectURL(url);
      }, 100);
      
    } catch (error) {
      console.error('Error downloading document:', error);
      console.error('Document object:', doc);
      const errorMessage = error.response?.data?.message || error.message;
      toast.error(t('Failed to download document: {{message}}', { message: errorMessage }));
    }
  };

  const getStatusColor = (status) => {
    switch (status) {
      case 'pending':
        return 'badge-warning';
      case 'approved':
        return 'badge-success';
      case 'rejected':
        return 'badge-error';
      default:
        return 'badge-neutral';
    }
  };

  if (loading) {
    return (
      <div className="container mx-auto p-6">
        <div className="flex justify-center items-center h-64">
          <span className="loading loading-spinner loading-lg"></span>
        </div>
      </div>
    );
  }

  return (
    <div className="container mx-auto p-6">
      <div className="flex justify-between items-center mb-6">
        <h1 className="text-3xl font-bold">{t('Admin Dashboard')}</h1>
        <div className="flex gap-2">
          <button onClick={fetchProfile} className="btn btn-outline">
            {t('Update Profile')}
          </button>
          <button onClick={() => setPasswordModal(true)} className="btn btn-outline">
            {t('Change Password')}
          </button>
        </div>
      </div>

      <div className="card bg-base-100 shadow-xl">
        <div className="card-body">
          <h2 className="card-title text-2xl mb-4">{t('Company Registrations')}</h2>
          
          {companies.length === 0 ? (
            <p className="text-center text-base-content/70 py-8">{t('No companies registered yet.')}</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="table table-zebra">
                <thead>
                  <tr>
                    <th>{t('Company Name')}</th>
                    <th>{t('Owner Email')}</th>
                    <th>{t('Owner Name')}</th>
                    <th>{t('Status')}</th>
                    <th>{t('Documents')}</th>
                    <th>{t('Actions')}</th>
                  </tr>
                </thead>
                <tbody>
                  {companies.map((company) => (
                    <tr key={company.id}>
                      <td>{company.name}</td>
                      <td>{company.owner_email}</td>
                      <td>{`${company.owner_first_name} ${company.owner_last_name}`}</td>
                      <td>
                        <span className={`badge ${getStatusColor(company.status)}`}>
                          {t(company.status)}
                        </span>
                      </td>
                      <td>
                        {company.business_documents && company.business_documents.length > 0 ? (
                          <div className="flex flex-col gap-1">
                            {company.business_documents.map((doc, idx) => (
                              <button
                                key={idx}
                                onClick={(e) => {
                                  e.preventDefault();
                                  e.stopPropagation();
                                  downloadDocument(doc);
                                }}
                                className={`btn btn-sm btn-link text-left hover:text-primary ${doc.isOldFormat ? 'opacity-50' : ''}`}
                            title={doc.isOldFormat ? t('This document was uploaded before database storage. File may not be available.') : ''}
                              >
                            📄 {doc.originalname || doc.filename}
                            {doc.isOldFormat && ` ${t('(Legacy)')}`}
                              </button>
                            ))}
                          </div>
                        ) : (
                          <span className="text-base-content/50">{t('No documents')}</span>
                        )}
                      </td>
                      <td>
                        <div className="flex gap-2">
                          {company.status === 'pending' && (
                            <>
                              <button
                                onClick={() => handleApprove(company.id)}
                                className="btn btn-sm btn-success"
                              >
                                {t('Approve')}
                              </button>
                              <button
                                onClick={() => setRejectModal({ open: true, companyId: company.id, rejectionMessage: '' })}
                                className="btn btn-sm btn-error"
                              >
                                {t('Reject')}
                              </button>
                            </>
                          )}
                          {company.status === 'rejected' && (
                            <button
                              onClick={() => handleDelete(company.id)}
                              className="btn btn-sm btn-error"
                            >
                              {t('Delete')}
                            </button>
                          )}
                          {company.status === 'approved' && (
                            <span className="text-success">✓ {t('Approved')}</span>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>

      {/* Reject Modal */}
      {rejectModal.open && (
        <div className="modal modal-open">
          <div className="modal-box">
            <h3 className="font-bold text-lg mb-4">{t('Reject Company')}</h3>
            <div className="form-control mb-4">
              <label className="label">
                <span className="label-text">{t('Rejection Reason')}</span>
              </label>
              <textarea
                className="textarea textarea-bordered"
                placeholder={t('Enter rejection reason...')}
                value={rejectModal.rejectionMessage}
                onChange={(e) => setRejectModal({ ...rejectModal, rejectionMessage: e.target.value })}
                rows={4}
              />
            </div>
            <div className="modal-action">
              <button
                onClick={() => setRejectModal({ open: false, companyId: null, rejectionMessage: '' })}
                className="btn"
              >
                {t('Cancel')}
              </button>
              <button onClick={handleReject} className="btn btn-error">
                {t('Reject')}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Profile Modal */}
      {profileModal && (
        <div className="modal modal-open">
          <div className="modal-box">
            <h3 className="font-bold text-lg mb-4">{t('Update Profile')}</h3>
            <div className="form-control mb-4">
              <label className="label">
                <span className="label-text">{t('Email')}</span>
              </label>
              <input
                type="email"
                className="input input-bordered"
                value={profileData.email}
                onChange={(e) => setProfileData({ ...profileData, email: e.target.value })}
              />
            </div>
            <div className="grid grid-cols-2 gap-4 mb-4">
              <div className="form-control">
                <label className="label">
                  <span className="label-text">{t('First Name')}</span>
                </label>
                <input
                  type="text"
                  className="input input-bordered"
                  value={profileData.firstName}
                  onChange={(e) => setProfileData({ ...profileData, firstName: e.target.value })}
                />
              </div>
              <div className="form-control">
                <label className="label">
                  <span className="label-text">{t('Last Name')}</span>
                </label>
                <input
                  type="text"
                  className="input input-bordered"
                  value={profileData.lastName}
                  onChange={(e) => setProfileData({ ...profileData, lastName: e.target.value })}
                />
              </div>
            </div>
            <div className="form-control mb-4">
              <label className="label">
                <span className="label-text">{t('Phone')}</span>
              </label>
              <input
                type="tel"
                className="input input-bordered"
                value={profileData.phone}
                onChange={(e) => setProfileData({ ...profileData, phone: e.target.value })}
              />
            </div>
            <div className="modal-action">
              <button
                onClick={() => setProfileModal(false)}
                className="btn"
              >
                {t('Cancel')}
              </button>
              <button onClick={handleUpdateProfile} className="btn btn-primary">
                {t('Update')}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Password Modal */}
      {passwordModal && (
        <div className="modal modal-open">
          <div className="modal-box">
            <h3 className="font-bold text-lg mb-4">{t('Change Password')}</h3>
            <div className="form-control mb-4">
              <label className="label">
                <span className="label-text">{t('Current Password')}</span>
              </label>
              <input
                type="password"
                className="input input-bordered"
                value={passwordData.currentPassword}
                onChange={(e) => setPasswordData({ ...passwordData, currentPassword: e.target.value })}
              />
            </div>
            <div className="form-control mb-4">
              <label className="label">
                <span className="label-text">{t('New Password')}</span>
              </label>
              <input
                type="password"
                className="input input-bordered"
                value={passwordData.newPassword}
                onChange={(e) => setPasswordData({ ...passwordData, newPassword: e.target.value })}
              />
            </div>
            <div className="form-control mb-4">
              <label className="label">
                <span className="label-text">{t('Confirm Password')}</span>
              </label>
              <input
                type="password"
                className="input input-bordered"
                value={passwordData.confirmPassword}
                onChange={(e) => setPasswordData({ ...passwordData, confirmPassword: e.target.value })}
              />
            </div>
            <div className="modal-action">
              <button
                onClick={() => {
                  setPasswordModal(false);
                  setPasswordData({ currentPassword: '', newPassword: '', confirmPassword: '' });
                }}
                className="btn"
              >
                {t('Cancel')}
              </button>
              <button onClick={handleChangePassword} className="btn btn-primary">
                {t('Change Password')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default AdminDashboard;

